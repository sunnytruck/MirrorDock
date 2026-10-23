/* SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 MirrorDock contributors.
 * MirrorDock's optional, metadata-only UxPlay instrumentation. GPL-3.0-or-later. */
#ifndef MD_PERFORMANCE_H
#define MD_PERFORMANCE_H
#include <gst/gst.h>
#ifdef __cplusplus
extern "C" {
#endif
void md_perf_control_start(void);
void md_perf_register_pipeline(GstElement *pipeline);
void md_perf_unregister_pipeline(GstElement *pipeline);
#ifdef MD_PERF_TESTING
/* The production reader uses this exact parser; tests schedule the same commands. */
gboolean md_perf_test_command(const char *line);
guint md_perf_test_probe_count(void);
gboolean md_perf_test_active(void);
#endif
#ifdef __cplusplus
}
#endif
#endif
