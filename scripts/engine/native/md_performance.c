/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 MirrorDock contributors.
 * MirrorDock optional performance instrumentation for the pinned UxPlay source.
 * GPL-3.0-or-later. No buffer contents, addresses, paths, caps or device IDs
 * are exported. Outside a diagnostic session there are no frame probes or
 * sampling timers. The optional private stdin reader is blocked while idle.
 */
#include "md_performance.h"
#include <gst/base/gstbasesink.h>
#include <gst/video/gstvideodecoder.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <io.h>
#define md_read _read
#else
#include <unistd.h>
#define md_read read
#endif

#define MD_GAPS 512
#define MD_MAX_PROBES 64
#define MD_MAX_PIPELINES 8

typedef struct {
    gint64 values[MD_GAPS];
    guint count, next;
    gint64 previous, maximum;
} MdGaps;

typedef struct MdRun MdRun;
typedef struct {
    MdRun *run;
    GstPad *pad;
    gulong id;
    guint stage;
    gchar decoder[64];
} MdProbe;

struct MdRun {
    gint references;
    GMutex lock;
    gboolean accepting, detailed_present, interrupted;
    guint64 sid;
    gint64 started, deadline;
    guint segment;
    GstElement *pipeline, *sink;
    GstBus *bus;
    gulong bus_handler;
    GstPad *source_pad, *decoder_pad;
    gboolean decoder_ambiguous;
    GPtrArray *probes;
    gulong present_handler;
    gboolean original_emit_present;
    gboolean have_present_property;
    gboolean source_seen, decoded_seen, present_seen;
    guint64 source_total, decoded_total, present_total;
    MdGaps source_gaps, decode_gaps, present_gaps;
    guint64 rendered_base, dropped_base, rendered_last, dropped_last;
    gboolean sink_baseline;
    gchar decoder[64];
};

static GMutex md_control_lock;
static GMutex md_output_lock;
static GPtrArray *md_pipelines;
static MdRun *md_run;
static guint md_timer;
static gsize md_reader_started;

static gboolean md_enabled(void) {
    return g_strcmp0(g_getenv("MIRRORDOCK_PERF_CONTROL"), "1") == 0;
}

static void md_line(const gchar *line) {
    g_mutex_lock(&md_output_lock);
    /* One stdio call keeps each bounded protocol line together with respect
     * to the engine's other stdio writers. Never echo untrusted commands. */
    printf("%s\n", line);
    fflush(stdout);
    g_mutex_unlock(&md_output_lock);
}

static MdRun *md_ref(MdRun *run) {
    g_atomic_int_inc(&run->references);
    return run;
}

static void md_unref(MdRun *run) {
    if (!g_atomic_int_dec_and_test(&run->references)) return;
    if (run->pipeline) gst_object_unref(run->pipeline);
    if (run->sink) gst_object_unref(run->sink);
    if (run->bus) gst_object_unref(run->bus);
    if (run->source_pad) gst_object_unref(run->source_pad);
    if (run->decoder_pad) gst_object_unref(run->decoder_pad);
    g_ptr_array_free(run->probes, TRUE);
    g_mutex_clear(&run->lock);
    g_free(run);
}

static void md_gap(MdGaps *gap, gint64 now) {
    if (gap->previous) {
        gint64 value = MAX((gint64) 0, now - gap->previous);
        gap->values[gap->next] = value;
        gap->next = (gap->next + 1) % MD_GAPS;
        gap->count = MIN(gap->count + 1, MD_GAPS);
        gap->maximum = MAX(gap->maximum, value);
    }
    gap->previous = now;
}

static gint md_compare_gap(gconstpointer a, gconstpointer b) {
    gint64 left = *(const gint64 *)a, right = *(const gint64 *)b;
    return left < right ? -1 : left > right;
}

static gint64 md_p95(const MdGaps *gap) {
    gint64 values[MD_GAPS];
    if (!gap->count) return -1;
    memcpy(values, gap->values, sizeof(gint64) * gap->count);
    qsort(values, gap->count, sizeof(gint64), md_compare_gap);
    return values[((gap->count * 95 + 99) / 100) - 1];
}

static void md_token(gchar *target, gsize capacity, const gchar *source) {
    gsize length = 0;
    if (source) {
        while (source[length] && length + 1 < capacity) {
            const gchar c = source[length];
            if (!(g_ascii_isalnum(c) || c == '_' || c == '-' || c == '+')) {
                g_strlcpy(target, "unknown", capacity);
                return;
            }
            target[length] = c;
            length++;
        }
    }
    target[length] = '\0';
    if (!length || (source && source[length])) g_strlcpy(target, "unknown", capacity);
}

static gboolean md_is_au(GstPad *pad) {
    GstCaps *caps = gst_pad_get_current_caps(pad);
    gboolean valid = FALSE;
    if (caps && gst_caps_get_size(caps) == 1) {
        GstStructure *structure = gst_caps_get_structure(caps, 0);
        const gchar *name = gst_structure_get_name(structure);
        const gchar *alignment = gst_structure_get_string(structure, "alignment");
        valid = (g_strcmp0(name, "video/x-h264") == 0 || g_strcmp0(name, "video/x-h265") == 0)
            && g_strcmp0(alignment, "au") == 0;
    }
    if (caps) gst_caps_unref(caps);
    return valid;
}

static void md_interrupt(MdRun *run);

static GstPadProbeReturn md_buffer_probe(GstPad *pad, GstPadProbeInfo *info, gpointer value) {
    MdProbe *probe = value;
    MdRun *run = probe->run;
    if (GST_PAD_PROBE_INFO_TYPE(info) & (GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM | GST_PAD_PROBE_TYPE_EVENT_FLUSH)) {
        GstEvent *event = GST_PAD_PROBE_INFO_EVENT(info);
        if (event && (GST_EVENT_TYPE(event) == GST_EVENT_FLUSH_START || GST_EVENT_TYPE(event) == GST_EVENT_FLUSH_STOP))
            md_interrupt(run);
        return GST_PAD_PROBE_OK;
    }
    GstBuffer *buffer = GST_PAD_PROBE_INFO_BUFFER(info);
    if (!buffer) return GST_PAD_PROBE_OK;
    /* Parser output is counted only after negotiation proves AU alignment.
     * Count no byte sizes, NAL contents or network packets. */
    if (probe->stage == 0 && !md_is_au(pad)) return GST_PAD_PROBE_OK;
    gint64 now = g_get_monotonic_time();
    g_mutex_lock(&run->lock);
    if (run->accepting) {
        if (probe->stage == 0) {
            if (!run->source_pad) run->source_pad = gst_object_ref(pad);
            /* A decodebin may contain another parser in series. Count one
             * actual AU stream, never add both copies of the same frame. */
            if (run->source_pad != pad) { g_mutex_unlock(&run->lock); return GST_PAD_PROBE_OK; }
            run->source_seen = TRUE;
            run->source_total++;
            md_gap(&run->source_gaps, now);
        } else if (probe->stage == 1) {
            if (!run->decoder_pad) run->decoder_pad = gst_object_ref(pad);
            if (run->decoder_pad != pad) {
                /* Multiple producing decoders are ambiguous: do not report
                 * an aggregate rate as one active decoder. */
                run->decoder_ambiguous = TRUE;
                g_mutex_unlock(&run->lock);
                return GST_PAD_PROBE_OK;
            }
            run->decoded_seen = TRUE;
            run->decoded_total++;
            md_gap(&run->decode_gaps, now);
            md_token(run->decoder, sizeof(run->decoder), probe->decoder);
        }
    }
    g_mutex_unlock(&run->lock);
    return GST_PAD_PROBE_OK;
}

static void md_probe_free(gpointer value) {
    MdProbe *probe = value;
    md_unref(probe->run);
    g_free(probe);
}

static void md_present(GstElement *sink, GstObject *device, gpointer target, gpointer value) {
    MdRun *run = value;
    (void)sink; (void)device; (void)target;
    gint64 now = g_get_monotonic_time();
    g_mutex_lock(&run->lock);
    if (run->accepting) {
        run->present_seen = TRUE;
        run->present_total++;
        md_gap(&run->present_gaps, now);
    }
    g_mutex_unlock(&run->lock);
}

static void md_closure_unref(gpointer value, GClosure *closure) {
    (void)closure;
    md_unref(value);
}

static gboolean md_sink_stats(GstElement *sink, guint64 *rendered, guint64 *dropped) {
    if (!sink || !GST_IS_BASE_SINK(sink)) return FALSE;
    GstStructure *stats = gst_base_sink_get_stats(GST_BASE_SINK(sink));
    if (!stats) return FALSE;
    gboolean valid = gst_structure_get_uint64(stats, "rendered", rendered)
        && gst_structure_get_uint64(stats, "dropped", dropped);
    gst_structure_free(stats);
    return valid;
}

static void md_add_probe(MdRun *run, GstElement *element, guint stage, const gchar *factory) {
    GstPad *pad = gst_element_get_static_pad(element, stage == 2 ? "sink" : "src");
    if (!pad) return;
    for (guint i = 0; i < run->probes->len; i++) {
        MdProbe *old = g_ptr_array_index(run->probes, i);
        if (old->pad == pad) { gst_object_unref(pad); return; }
    }
    if (run->probes->len >= MD_MAX_PROBES) { gst_object_unref(pad); return; }
    MdProbe *probe = g_new0(MdProbe, 1);
    probe->run = md_ref(run);
    probe->pad = pad;
    probe->stage = stage;
    md_token(probe->decoder, sizeof(probe->decoder), factory);
    GstPadProbeType type = GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM | GST_PAD_PROBE_TYPE_EVENT_FLUSH;
    if (stage != 2) type |= GST_PAD_PROBE_TYPE_BUFFER;
    probe->id = gst_pad_add_probe(pad, type, md_buffer_probe, probe, md_probe_free);
    if (!probe->id) {
        gst_object_unref(pad);
        md_probe_free(probe);
        return;
    }
    g_ptr_array_add(run->probes, probe);
}

static void md_discover(MdRun *run) {
    if (!run->pipeline) return;
    GstIterator *iterator = gst_bin_iterate_recurse(GST_BIN(run->pipeline));
    GValue item = G_VALUE_INIT;
    gboolean finished = FALSE;
    guint resyncs = 0;
    while (!finished) {
        switch (gst_iterator_next(iterator, &item)) {
        case GST_ITERATOR_OK: {
            GstElement *element = g_value_get_object(&item);
            GstElementFactory *factory = gst_element_get_factory(element);
            const gchar *name = factory ? gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory)) : NULL;
            if (g_strcmp0(name, "h264parse") == 0 || g_strcmp0(name, "h265parse") == 0)
                md_add_probe(run, element, 0, name);
            if (GST_IS_VIDEO_DECODER(element)) md_add_probe(run, element, 1, name);
            if (!run->sink && GST_IS_BASE_SINK(element)) {
                run->sink = gst_object_ref(element);
                md_add_probe(run, element, 2, NULL);
                run->sink_baseline = md_sink_stats(element, &run->rendered_base, &run->dropped_base);
                run->rendered_last = run->rendered_base;
                run->dropped_last = run->dropped_base;
                GParamSpec *property = g_object_class_find_property(G_OBJECT_GET_CLASS(element), "emit-present");
                guint signal = g_signal_lookup("present", G_OBJECT_TYPE(element));
                if (run->detailed_present && property && G_PARAM_SPEC_VALUE_TYPE(property) == G_TYPE_BOOLEAN &&
                    (property->flags & G_PARAM_READABLE) && (property->flags & G_PARAM_WRITABLE) && signal) {
                    GSignalQuery query;
                    g_signal_query(signal, &query);
                    if (query.return_type == G_TYPE_NONE && query.n_params == 2 &&
                        g_type_is_a(query.param_types[0] & ~G_SIGNAL_TYPE_STATIC_SCOPE, GST_TYPE_OBJECT) &&
                        (query.param_types[1] & ~G_SIGNAL_TYPE_STATIC_SCOPE) == G_TYPE_POINTER) {
                        run->have_present_property = TRUE;
                        g_object_get(element, "emit-present", &run->original_emit_present, NULL);
                        run->present_handler = g_signal_connect_data(element, "present", G_CALLBACK(md_present),
                            md_ref(run), md_closure_unref, 0);
                        g_object_set(element, "emit-present", TRUE, NULL);
                    }
                }
            }
            g_value_reset(&item);
            break;
        }
        case GST_ITERATOR_RESYNC:
            if (++resyncs > 8) finished = TRUE;
            else gst_iterator_resync(iterator);
            break;
        default:
            finished = TRUE;
            break;
        }
    }
    if (G_IS_VALUE(&item)) g_value_unset(&item);
    gst_iterator_free(iterator);
}

static void md_state_message(GstBus *bus, GstMessage *message, gpointer value) {
    MdRun *run = value;
    (void)bus;
    if (GST_MESSAGE_TYPE(message) == GST_MESSAGE_STATE_CHANGED &&
        GST_MESSAGE_SRC(message) == GST_OBJECT(run->pipeline)) {
        GstState old_state, new_state;
        gst_message_parse_state_changed(message, &old_state, &new_state, NULL);
        if (old_state == GST_STATE_PLAYING && new_state != GST_STATE_PLAYING) md_interrupt(run);
    }
}

/* Called only with the control mutex, never from a streaming callback. */
static void md_bind(MdRun *run) {
    if (run->pipeline || !md_pipelines) return;
    for (guint i = 0; i < md_pipelines->len; i++) {
        GstElement *pipeline = g_ptr_array_index(md_pipelines, i);
        GstState state = GST_STATE_NULL;
        gst_element_get_state(pipeline, &state, NULL, 0);
        if (state == GST_STATE_PLAYING) {
            run->pipeline = gst_object_ref(pipeline);
            run->segment++;
            run->bus = gst_element_get_bus(pipeline);
            if (run->bus) {
                run->bus_handler = g_signal_connect_data(run->bus, "sync-message::state-changed",
                    G_CALLBACK(md_state_message), md_ref(run), md_closure_unref, 0);
                gst_bus_enable_sync_message_emission(run->bus);
            }
            md_discover(run);
            return;
        }
    }
}

static void md_sample(MdRun *run) {
    guint64 rendered = 0, dropped = 0;
    gboolean have_sink = md_sink_stats(run->sink, &rendered, &dropped);
    gint64 rendered_total = -1, dropped_total = -1;
    g_mutex_lock(&run->lock);
    if (have_sink && run->sink_baseline && !run->interrupted) {
        if (rendered < run->rendered_last || dropped < run->dropped_last) {
            /* A pause/reset starts a new counter segment. Never turn a reset
             * into an enormous unsigned delta or combine unlike intervals. */
            run->segment++;
            run->rendered_base = rendered;
            run->dropped_base = dropped;
            run->source_total = run->decoded_total = run->present_total = 0;
            run->source_seen = run->decoded_seen = run->present_seen = FALSE;
            memset(&run->source_gaps, 0, sizeof(MdGaps));
            memset(&run->decode_gaps, 0, sizeof(MdGaps));
            memset(&run->present_gaps, 0, sizeof(MdGaps));
        }
        run->rendered_last = rendered;
        run->dropped_last = dropped;
        rendered_total = (gint64)(rendered - run->rendered_base);
        dropped_total = (gint64)(dropped - run->dropped_base);
    }
    gchar *line = g_strdup_printf(
        "MDPERF1 SAMPLE %" G_GUINT64_FORMAT " %" G_GINT64_FORMAT " %u %" G_GINT64_FORMAT
        " %" G_GINT64_FORMAT " %" G_GINT64_FORMAT " %" G_GINT64_FORMAT " %" G_GINT64_FORMAT
        " %" G_GINT64_FORMAT " %" G_GINT64_FORMAT " %" G_GINT64_FORMAT " %" G_GINT64_FORMAT
        " %" G_GINT64_FORMAT " %" G_GINT64_FORMAT " %s",
        run->sid, MAX((gint64)0, (g_get_monotonic_time() - run->started) / 1000), run->segment,
        run->source_seen && !run->interrupted ? (gint64)run->source_total : -1,
        run->decoded_seen && !run->decoder_ambiguous && !run->interrupted ? (gint64)run->decoded_total : -1,
        rendered_total, dropped_total, run->present_seen && !run->interrupted ? (gint64)run->present_total : -1,
        md_p95(&run->source_gaps), run->source_gaps.count ? run->source_gaps.maximum : -1,
        run->decoder_ambiguous ? -1 : md_p95(&run->decode_gaps),
        run->decoder_ambiguous ? -1 : run->decode_gaps.count ? run->decode_gaps.maximum : -1,
        md_p95(&run->present_gaps), run->present_gaps.count ? run->present_gaps.maximum : -1,
        run->decoded_seen && !run->decoder_ambiguous && !run->interrupted ? run->decoder : "unknown");
    g_mutex_unlock(&run->lock);
    md_line(line);
    g_free(line);
}

static void md_finish(gboolean from_timer) {
    MdRun *run = md_run;
    if (!run) return;
    g_mutex_lock(&run->lock);
    run->accepting = FALSE;
    g_mutex_unlock(&run->lock);
    md_sample(run);
    md_run = NULL;
    if (md_timer && !from_timer) g_source_remove(md_timer);
    md_timer = 0;
    for (guint i = 0; i < run->probes->len; i++) {
        MdProbe *probe = g_ptr_array_index(run->probes, i);
        /* The probe owns a pad reference separately from its destroy notify.
         * remove_probe may release the probe struct, so save that reference. */
        GstPad *pad = probe->pad;
        gulong id = probe->id;
        gst_pad_remove_probe(pad, id);
        gst_object_unref(pad);
    }
    g_ptr_array_set_size(run->probes, 0);
    if (run->bus_handler) {
        g_signal_handler_disconnect(run->bus, run->bus_handler);
        gst_bus_disable_sync_message_emission(run->bus);
    }
    if (run->present_handler) g_signal_handler_disconnect(run->sink, run->present_handler);
    if (run->have_present_property)
        g_object_set(run->sink, "emit-present", run->original_emit_present, NULL);
    gchar *line = g_strdup_printf("MDPERF1 DONE %" G_GUINT64_FORMAT, run->sid);
    md_line(line);
    g_free(line);
    md_unref(run);
}

static gboolean md_tick(gpointer unused) {
    (void)unused;
    g_mutex_lock(&md_control_lock);
    if (!md_run) { g_mutex_unlock(&md_control_lock); return G_SOURCE_REMOVE; }
    md_bind(md_run);
    md_discover(md_run);
    if (g_get_monotonic_time() >= md_run->deadline) {
        md_finish(TRUE);
        g_mutex_unlock(&md_control_lock);
        return G_SOURCE_REMOVE;
    }
    md_sample(md_run);
    g_mutex_unlock(&md_control_lock);
    return G_SOURCE_CONTINUE;
}

typedef struct { gboolean start, detailed_present; guint64 sid; guint seconds; } MdCommand;

static gboolean md_apply_command(gpointer value) {
    MdCommand *command = value;
    g_mutex_lock(&md_control_lock);
    if (command->start) {
        if (md_run) md_finish(FALSE);
        MdRun *run = g_new0(MdRun, 1);
        run->references = 1;
        g_mutex_init(&run->lock);
        run->accepting = TRUE;
        run->detailed_present = command->detailed_present;
        run->sid = command->sid;
        run->started = g_get_monotonic_time();
        run->deadline = run->started + (gint64)command->seconds * G_USEC_PER_SEC;
        run->probes = g_ptr_array_new();
        g_strlcpy(run->decoder, "unknown", sizeof(run->decoder));
        md_run = run;
        md_bind(run);
        gchar *line = g_strdup_printf("MDPERF1 STARTED %" G_GUINT64_FORMAT, run->sid);
        md_line(line);
        g_free(line);
        md_sample(run);
        md_timer = g_timeout_add(1000, md_tick, NULL);
    } else if (md_run && (command->sid == 0 || md_run->sid == command->sid)) {
        md_finish(FALSE);
    }
    g_mutex_unlock(&md_control_lock);
    return G_SOURCE_REMOVE;
}

static gboolean md_unsigned(const gchar *text, guint64 *value) {
    if (!text || !*text) return FALSE;
    for (const gchar *p = text; *p; p++) if (!g_ascii_isdigit(*p)) return FALSE;
    errno = 0;
    gchar *end = NULL;
    guint64 number = g_ascii_strtoull(text, &end, 10);
    if (errno || !end || *end || !number || number > G_MAXINT64) return FALSE;
    *value = number;
    return TRUE;
}

static void md_queue_command(MdCommand *command) {
    GSource *source = g_idle_source_new();
    g_source_set_callback(source, md_apply_command, command, g_free);
    g_source_attach(source, g_main_context_default());
    g_source_unref(source);
}

/* Flush/state reset stops this session instead of mixing counters from before
 * and after a reset. The synchronous bus/event hook invalidates immediately;
 * actual teardown is queued to the owning main context, never a streaming
 * callback. This also catches reset-and-resume between 1Hz samples. */
static void md_interrupt(MdRun *run) {
    g_mutex_lock(&run->lock);
    gboolean queue = run->accepting && !run->interrupted;
    if (queue) {
        run->accepting = FALSE;
        run->interrupted = TRUE;
        run->segment++;
        run->source_seen = run->decoded_seen = run->present_seen = FALSE;
        memset(&run->source_gaps, 0, sizeof(MdGaps));
        memset(&run->decode_gaps, 0, sizeof(MdGaps));
        memset(&run->present_gaps, 0, sizeof(MdGaps));
    }
    g_mutex_unlock(&run->lock);
    if (queue) {
        MdCommand *stop = g_new0(MdCommand, 1);
        stop->sid = run->sid;
        md_queue_command(stop);
    }
}

static gboolean md_command(const gchar *line) {
    if (!md_enabled() || !line || strlen(line) > 127) return FALSE;
    gchar **raw = g_strsplit_set(line, " \t\r\n", -1);
    const gchar *tokens[6];
    guint count = 0;
    for (guint i = 0; raw[i]; i++) {
        if (!*raw[i]) continue;
        if (count >= G_N_ELEMENTS(tokens)) { count = 6; break; }
        tokens[count++] = raw[i];
    }
    guint64 sid = 0, seconds = 0;
    gboolean valid = count >= 3 && g_strcmp0(tokens[0], "MDPERF1") == 0 && md_unsigned(tokens[2], &sid);
    gboolean detailed = count == 5 && g_strcmp0(tokens[4], "PRESENT") == 0;
    gboolean start = valid && (count == 4 || detailed) && g_strcmp0(tokens[1], "START") == 0
        && md_unsigned(tokens[3], &seconds) && seconds <= 60;
    gboolean stop = valid && count == 3 && g_strcmp0(tokens[1], "STOP") == 0;
    if (start || stop) {
        MdCommand *command = g_new0(MdCommand, 1);
        command->start = start;
        command->detailed_present = detailed;
        command->sid = sid;
        command->seconds = (guint)seconds;
        md_queue_command(command);
    }
    g_strfreev(raw);
    return start || stop;
}

static gpointer md_reader(gpointer unused) {
    (void)unused;
    gchar line[128], chunk[128];
    gsize length = 0;
    gboolean discard = FALSE;
    for (;;) {
        /* Raw descriptor I/O avoids holding stdin's FILE lock while blocked.
         * A fgets reader can deadlock the C runtime's exit-time stream flush. */
        int count = (int)md_read(0, chunk, sizeof(chunk));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        for (int i = 0; i < count; i++) {
            if (chunk[i] == '\n') {
                if (!discard) { line[length] = '\0'; md_command(line); }
                length = 0;
                discard = FALSE;
            } else if (chunk[i] == '\0' || length >= sizeof(line) - 1) {
                discard = TRUE;
            } else if (!discard) line[length++] = chunk[i];
        }
    }
    MdCommand *stop = g_new0(MdCommand, 1);
    /* EOF cancels instrumentation only, never the receiver itself. */
    md_queue_command(stop);
    return NULL;
}

void md_perf_control_start(void) {
    if (!md_enabled()) return;
    if (g_once_init_enter(&md_reader_started)) {
        GThread *thread = g_thread_new("md-perf-control", md_reader, NULL);
        g_thread_unref(thread);
        md_line("MDPERF1 READY");
        g_once_init_leave(&md_reader_started, 1);
    }
}

void md_perf_register_pipeline(GstElement *pipeline) {
    if (!md_enabled() || !GST_IS_BIN(pipeline)) return;
    g_mutex_lock(&md_control_lock);
    if (!md_pipelines) md_pipelines = g_ptr_array_new_with_free_func((GDestroyNotify)gst_object_unref);
    gboolean found = FALSE;
    for (guint i = 0; i < md_pipelines->len; i++) if (g_ptr_array_index(md_pipelines, i) == pipeline) found = TRUE;
    if (!found && md_pipelines->len < MD_MAX_PIPELINES) g_ptr_array_add(md_pipelines, gst_object_ref(pipeline));
    g_mutex_unlock(&md_control_lock);
}

void md_perf_unregister_pipeline(GstElement *pipeline) {
    if (!md_enabled()) return;
    g_mutex_lock(&md_control_lock);
    if (md_run && md_run->pipeline == pipeline) md_finish(FALSE);
    if (md_pipelines) {
        for (guint i = 0; i < md_pipelines->len; i++) {
            if (g_ptr_array_index(md_pipelines, i) == pipeline) {
                g_ptr_array_remove_index(md_pipelines, i);
                break;
            }
        }
    }
    g_mutex_unlock(&md_control_lock);
}

#ifdef MD_PERF_TESTING
gboolean md_perf_test_command(const char *line) { return md_command(line); }
guint md_perf_test_probe_count(void) {
    g_mutex_lock(&md_control_lock);
    guint count = md_run ? md_run->probes->len : 0;
    g_mutex_unlock(&md_control_lock);
    return count;
}
gboolean md_perf_test_active(void) {
    g_mutex_lock(&md_control_lock);
    gboolean active = md_run != NULL;
    g_mutex_unlock(&md_control_lock);
    return active;
}
#endif
