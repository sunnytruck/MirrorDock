/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 MirrorDock contributors.
 * Synthetic tests only. No display, audio device, LAN or media file is used.
 */
#define _POSIX_C_SOURCE 200809L
#include "md_performance.h"
#include <gst/base/gstbasesink.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <io.h>
#define md_fdopen _fdopen
#define md_close _close
#else
#include <unistd.h>
#define md_fdopen fdopen
#define md_close close
#endif

/* This synthetic sink exercises the optional present API without a GPU. It is
 * not evidence that Windows D3D11 or a physical display was tested. */
typedef struct { GstBaseSink parent; gboolean emit_present; } MdTestSink;
typedef struct { GstBaseSinkClass parent; } MdTestSinkClass;
G_DEFINE_TYPE(MdTestSink, md_test_sink, GST_TYPE_BASE_SINK)
static guint present_signal;
static GstStaticPadTemplate sink_template = GST_STATIC_PAD_TEMPLATE("sink", GST_PAD_SINK,
    GST_PAD_ALWAYS, GST_STATIC_CAPS_ANY);
static void test_sink_set(GObject *object, guint id, const GValue *value, GParamSpec *spec) {
    if (id == 1) ((MdTestSink *)object)->emit_present = g_value_get_boolean(value);
    else G_OBJECT_WARN_INVALID_PROPERTY_ID(object, id, spec);
}
static void test_sink_get(GObject *object, guint id, GValue *value, GParamSpec *spec) {
    if (id == 1) g_value_set_boolean(value, ((MdTestSink *)object)->emit_present);
    else G_OBJECT_WARN_INVALID_PROPERTY_ID(object, id, spec);
}
static GstFlowReturn test_sink_render(GstBaseSink *sink, GstBuffer *buffer) {
    (void)buffer;
    if (((MdTestSink *)sink)->emit_present) g_signal_emit(sink, present_signal, 0, NULL, NULL);
    return GST_FLOW_OK;
}
static void md_test_sink_class_init(MdTestSinkClass *type) {
    GObjectClass *object = G_OBJECT_CLASS(type);
    object->set_property = test_sink_set;
    object->get_property = test_sink_get;
    g_object_class_install_property(object, 1, g_param_spec_boolean("emit-present", "Emit present",
        "Synthetic present callback", FALSE, G_PARAM_READWRITE));
    present_signal = g_signal_new("present", G_TYPE_FROM_CLASS(type), G_SIGNAL_RUN_LAST, 0,
        NULL, NULL, NULL, G_TYPE_NONE, 2, GST_TYPE_OBJECT, G_TYPE_POINTER);
    gst_element_class_add_static_pad_template(GST_ELEMENT_CLASS(type), &sink_template);
    gst_element_class_set_static_metadata(GST_ELEMENT_CLASS(type), "Synthetic sink", "Sink/Video",
        "Tests metadata telemetry without a GPU", "MirrorDock contributors");
    GST_BASE_SINK_CLASS(type)->render = test_sink_render;
}
static void md_test_sink_init(MdTestSink *sink) { sink->emit_present = FALSE; }

static gboolean have_factory(const char *name) {
    GstElementFactory *factory = gst_element_factory_find(name);
    if (!factory) return FALSE;
    gst_object_unref(factory);
    return TRUE;
}

static const char *decoder_factory(void) {
    if (have_factory("avdec_h264")) return "avdec_h264";
    if (have_factory("openh264dec")) return "openh264dec";
    return NULL;
}

static GstElement *make_pipeline(gboolean encoded) {
    const char *decoder = decoder_factory();
    gchar *description;
    if (encoded && decoder && have_factory("openh264enc") && have_factory("h264parse")) {
        description = g_strdup_printf("videotestsrc is-live=true ! video/x-raw,width=160,height=90,framerate=60/1 "
            "! openh264enc complexity=low ! h264parse ! h264parse ! video/x-h264,alignment=au ! %s "
            "! mdtestpresentsink name=output sync=true", decoder);
    } else {
        g_assert_false(encoded);
        description = g_strdup("videotestsrc is-live=true ! video/x-raw,width=160,height=90,framerate=60/1 "
            "! mdtestpresentsink name=output sync=true");
    }
    GError *error = NULL;
    GstElement *pipeline = gst_parse_launch(description, &error);
    g_free(description);
    g_assert_no_error(error);
    g_assert_nonnull(pipeline);
    g_assert_cmpint(gst_element_set_state(pipeline, GST_STATE_PLAYING), !=, GST_STATE_CHANGE_FAILURE);
    GstState state;
    g_assert_cmpint(gst_element_get_state(pipeline, &state, NULL, 5 * GST_SECOND), !=, GST_STATE_CHANGE_FAILURE);
    g_assert_cmpint(state, ==, GST_STATE_PLAYING);
    return pipeline;
}

static GMainLoop *receiver_loop;
static GstElement *receiver_pipeline;
static gboolean receiver_seen_active;
static gboolean receiver_completed;
static gboolean receiver_reset;
static gint64 receiver_reset_at;
static gboolean receiver_tick(gpointer unused) {
    (void)unused;
    if (md_perf_test_active()) {
        receiver_seen_active = TRUE;
        if (!receiver_reset_at) receiver_reset_at = g_get_monotonic_time() + 1250000;
        if (receiver_reset && g_get_monotonic_time() >= receiver_reset_at) {
            receiver_reset = FALSE;
            gst_element_set_state(receiver_pipeline, GST_STATE_PAUSED);
            gst_element_set_state(receiver_pipeline, GST_STATE_PLAYING);
        }
    } else if (receiver_seen_active) {
        g_assert_cmpuint(md_perf_test_probe_count(), ==, 0);
        GstElement *sink = gst_bin_get_by_name(GST_BIN(receiver_pipeline), "output");
        gboolean enabled = TRUE;
        g_object_get(sink, "emit-present", &enabled, NULL);
        gst_object_unref(sink);
        g_assert_false(enabled);
        receiver_completed = TRUE;
        /* Leave the synthetic stream running briefly after STOP: detached
         * streaming probes must neither crash nor produce more samples. */
        g_main_loop_quit(receiver_loop);
        return G_SOURCE_REMOVE;
    }
    return G_SOURCE_CONTINUE;
}
static gboolean receiver_deadline(gpointer unused) {
    (void)unused;
    g_error("Synthetic stdin diagnostic did not finish within 8 seconds");
    return G_SOURCE_REMOVE;
}
static int receiver(gboolean encoded) {
    receiver_pipeline = make_pipeline(encoded);
    md_perf_register_pipeline(receiver_pipeline);
    g_assert_cmpuint(md_perf_test_probe_count(), ==, 0);
    /* Exercise a nonzero pre-session sink baseline, not lifetime stats. */
    g_usleep(250000);
    md_perf_control_start();
    receiver_loop = g_main_loop_new(NULL, FALSE);
    guint tick = g_timeout_add(25, receiver_tick, NULL);
    guint deadline = g_timeout_add_seconds(8, receiver_deadline, NULL);
    g_main_loop_run(receiver_loop);
    (void)tick;
    g_source_remove(deadline);
    g_assert_true(receiver_completed);
    g_usleep(100000);
    md_perf_unregister_pipeline(receiver_pipeline);
    gst_element_set_state(receiver_pipeline, GST_STATE_NULL);
    gst_object_unref(receiver_pipeline);
    g_main_loop_unref(receiver_loop);
    return 0;
}

static gint64 value_at(gchar **parts, guint index) {
    gchar *end = NULL;
    gint64 value = g_ascii_strtoll(parts[index], &end, 10);
    g_assert_nonnull(end);
    g_assert_cmpstr(end, ==, "");
    return value;
}

static void run_stdin_case(const char *executable, gboolean encoded, gboolean stop_early, gboolean detailed, gboolean reset) {
    gchar *arguments[] = {(gchar *)executable, encoded ? "--receiver-h264" : reset ? "--receiver-reset" : "--receiver-raw", NULL};
    gchar **environment = g_get_environ();
    environment = g_environ_setenv(environment, "MIRRORDOCK_PERF_CONTROL", "1", TRUE);
    environment = g_environ_setenv(environment, "GST_DEBUG", "0", TRUE);
    GPid pid;
    gint input_fd, output_fd;
    GError *error = NULL;
    g_assert_true(g_spawn_async_with_pipes(NULL, arguments, environment, G_SPAWN_DEFAULT,
        NULL, NULL, &pid, &input_fd, &output_fd, NULL, &error));
    g_assert_no_error(error);
    g_strfreev(environment);
    FILE *input = md_fdopen(input_fd, "w");
    FILE *output = md_fdopen(output_fd, "r");
    g_assert_nonnull(input);
    g_assert_nonnull(output);
    gchar line[2048];
    g_assert_nonnull(fgets(line, sizeof(line), output));
    g_assert_cmpstr(g_strstrip(line), ==, "MDPERF1 READY");
    /* The first malformed lines must not be echoed or start a diagnostic. */
    fputs("SECRET-192.0.2.1 MDPERF1 START 99 60\nMDPERF1 START 0 1\n"
          "MDPERF1 START 42 61\nMDPERF1 START 42 1 EXTRA\n", input);
    for (guint i = 0; i < 256; i++) fputc('x', input);
    fputc('\n', input);
    fprintf(input, "MDPERF1 START 42 %u%s\n", stop_early || reset ? 60 : 1, detailed ? " PRESENT" : "");
    fflush(input);
    gboolean started = FALSE, done = FALSE, sent_stop = FALSE, measured = FALSE;
    guint samples = 0;
    gint64 last_rendered = -1, last_source = -1, last_decoded = -1;
    while (fgets(line, sizeof(line), output)) {
        gchar *stripped = g_strstrip(line);
        g_assert_null(strstr(stripped, "SECRET"));
        g_assert_null(strstr(stripped, "192.0.2.1"));
        gchar **parts = g_strsplit(stripped, " ", -1);
        guint count = g_strv_length(parts);
        g_assert_cmpstr(parts[0], ==, "MDPERF1");
        if (g_strcmp0(parts[1], "STARTED") == 0) {
            g_assert_cmpuint(count, ==, 3);
            g_assert_cmpstr(parts[2], ==, "42");
            g_assert_false(started);
            started = TRUE;
        } else if (g_strcmp0(parts[1], "SAMPLE") == 0) {
            g_assert_true(started);
            g_assert_false(done);
            g_assert_cmpuint(count, ==, 17);
            g_assert_cmpint(value_at(parts, 2), ==, 42);
            gint64 elapsed = value_at(parts, 3);
            g_assert_cmpint(elapsed, >=, 0);
            gint64 segment = value_at(parts, 4);
            if (reset && segment == 2) {
                for (guint i = 5; i <= 15; i++) g_assert_cmpint(value_at(parts, i), ==, -1);
                g_assert_cmpstr(parts[16], ==, "unknown");
                g_strfreev(parts);
                continue;
            }
            g_assert_cmpint(segment, ==, 1);
            gint64 source = value_at(parts, 5), decoded = value_at(parts, 6), rendered = value_at(parts, 7);
            g_assert_cmpint(rendered, >=, last_rendered);
            g_assert_cmpint(value_at(parts, 8), >=, 0);
            if (elapsed >= 900) {
                /* The synthetic 60fps stream must produce real measured output.
                 * These loose bounds tolerate a busy CI host; they do not claim
                 * an end-to-end AirPlay frame rate or latency guarantee. */
                g_assert_cmpint(rendered, >, 10);
                g_assert_cmpint(rendered, <, 110);
                if (detailed) {
                    g_assert_cmpint(value_at(parts, 9), >, 10);
                    g_assert_cmpint(value_at(parts, 14), >, 0);
                    g_assert_cmpint(value_at(parts, 15), >=, value_at(parts, 14));
                } else {
                    g_assert_cmpint(value_at(parts, 9), ==, -1);
                    g_assert_cmpint(value_at(parts, 14), ==, -1);
                    g_assert_cmpint(value_at(parts, 15), ==, -1);
                }
                if (encoded) {
                    g_assert_cmpint(source, >, 10);
                    g_assert_cmpint(decoded, >, 10);
                    /* Two sequential parsers still represent one 60fps AU stream. */
                    g_assert_cmpint(source, <, 110);
                    g_assert_cmpint(source, <=, decoded + 8);
                    g_assert_cmpstr(parts[16], ==, decoder_factory());
                    g_assert_cmpint(value_at(parts, 10), >, 0);
                    g_assert_cmpint(value_at(parts, 12), >, 0);
                } else {
                    g_assert_cmpint(source, ==, -1);
                    g_assert_cmpint(decoded, ==, -1);
                    g_assert_cmpstr(parts[16], ==, "unknown");
                    for (guint i = 10; i <= 13; i++) g_assert_cmpint(value_at(parts, i), ==, -1);
                }
                measured = TRUE;
                if (stop_early && !sent_stop) {
                    /* Wrong-session STOP must leave the active measurement on. */
                    fputs("MDPERF1 STOP 999\nMDPERF1 STOP 42\n", input);
                    fflush(input);
                    sent_stop = TRUE;
                }
            }
            if (last_source >= 0) g_assert_cmpint(source, >=, last_source);
            if (last_decoded >= 0) g_assert_cmpint(decoded, >=, last_decoded);
            last_source = source;
            last_decoded = decoded;
            last_rendered = rendered;
            samples++;
        } else if (g_strcmp0(parts[1], "DONE") == 0) {
            g_assert_cmpuint(count, ==, 3);
            g_assert_cmpstr(parts[2], ==, "42");
            g_assert_false(done);
            done = TRUE;
        } else g_error("Unexpected synthetic protocol record");
        g_strfreev(parts);
    }
    fclose(input);
    fclose(output);
    g_spawn_close_pid(pid);
    g_assert_true(started);
    g_assert_true(done);
    g_assert_true(measured);
    g_assert_cmpuint(samples, >=, 2);
    g_assert_cmpint(sent_stop, ==, stop_early);
}

int main(int argc, char **argv) {
    gst_init(&argc, &argv);
    g_assert_true(gst_element_register(NULL, "mdtestpresentsink", GST_RANK_NONE, md_test_sink_get_type()));
    if (argc == 2 && g_str_has_prefix(argv[1], "--receiver-")) {
        receiver_reset = g_strcmp0(argv[1], "--receiver-reset") == 0;
        return receiver(g_strcmp0(argv[1], "--receiver-h264") == 0);
    }
    g_unsetenv("MIRRORDOCK_PERF_CONTROL");
    GstElement *off = make_pipeline(FALSE);
    md_perf_register_pipeline(off);
    g_assert_false(md_perf_test_command("MDPERF1 START 1 1"));
    g_assert_false(md_perf_test_active());
    g_assert_cmpuint(md_perf_test_probe_count(), ==, 0);
    md_perf_unregister_pipeline(off);
    gst_element_set_state(off, GST_STATE_NULL);
    gst_object_unref(off);
    g_setenv("MIRRORDOCK_PERF_CONTROL", "1", TRUE);
    const char *invalid[] = {"", "MDPERF1", "MDPERF1 START -1 1", "MDPERF1 START 1 0",
        "MDPERF1 START 1 61", "MDPERF1 START 9223372036854775808 1", "MDPERF1 STOP 0",
        "MDPERF1 STOP 1 extra", "MDPERF1 START 1 1 extra", "MDPERF1 START 1x 1"};
    for (guint i = 0; i < G_N_ELEMENTS(invalid); i++) g_assert_false(md_perf_test_command(invalid[i]));
    run_stdin_case(argv[0], FALSE, FALSE, FALSE, FALSE);
    run_stdin_case(argv[0], FALSE, TRUE, TRUE, FALSE);
    run_stdin_case(argv[0], FALSE, FALSE, FALSE, TRUE);
    if (decoder_factory() && have_factory("openh264enc") && have_factory("h264parse")) {
        run_stdin_case(argv[0], TRUE, TRUE, TRUE, FALSE);
        fputs("Native diagnostics: raw and H264 stdin integration tests passed (synthetic only).\n", stderr);
    } else {
        fputs("Native diagnostics: raw stdin tests passed; H264 test unavailable (missing synthetic codec plugin).\n", stderr);
        if (g_strcmp0(g_getenv("MD_PERF_REQUIRE_H264_TEST"), "1") == 0) return 2;
    }
    return 0;
}
