/* Quantitative device probe for the actual bundled FFmpeg audio graph.
 * Build with scripts/build-audio-filters.py; run beside original dependency .so files.
 */
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "libavfilter/avfilter.h"
#include "libavfilter/buffersink.h"
#include "libavfilter/buffersrc.h"
#include "libavutil/channel_layout.h"
#include "libavutil/error.h"
#include "libavutil/opt.h"
#include "libavutil/time.h"

#define PRESET "acompressor=threshold=0.0794328:ratio=4:attack=10:release=300:knee=2.82843:makeup=1.995262:link=maximum:detection=rms,alimiter=limit=0.891251:attack=5:release=100:level=false:latency=true"
#define RATE 48000
#define TOTAL (RATE * 5)
#define BLOCK 1024

typedef struct Stats {
    long samples;
    long quiet_n, loud_n;
    double quiet_sum, loud_sum, peak;
    int channels;
} Stats;

static void fail(int ret, const char *where) {
    char error[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(ret, error, sizeof(error));
    fprintf(stderr, "%s: %s (%d)\n", where, error, ret);
    exit(1);
}

static void check(int ret, const char *where) {
    if (ret < 0) fail(ret, where);
}

static void drain(AVFilterContext *sink, Stats *s) {
    AVFrame *f = av_frame_alloc();
    if (!f) fail(AVERROR(ENOMEM), "output frame");
    int ret;
    while ((ret = av_buffersink_get_frame(sink, f)) >= 0) {
        if (f->format != AV_SAMPLE_FMT_FLT || f->sample_rate != RATE ||
            f->ch_layout.nb_channels != s->channels)
            fail(AVERROR(EINVAL), "output format/rate/channels changed");
        const float *data = (float *)f->data[0];
        for (int n = 0; n < f->nb_samples; ++n) {
            long pos = s->samples + n;
            for (int c = 0; c < s->channels; ++c) {
                double value = data[n * s->channels + c];
                if (!isfinite(value)) fail(AVERROR(EINVAL), "nonfinite sample");
                if (fabs(value) > s->peak) s->peak = fabs(value);
                /* Skip attack/release transitions and the transient at 3 seconds. */
                if (pos >= RATE && pos < RATE * 3 / 2) {
                    s->quiet_sum += value * value;
                    ++s->quiet_n;
                }
                if (pos >= RATE * 5 / 2 && pos < RATE * 29 / 10) {
                    s->loud_sum += value * value;
                    ++s->loud_n;
                }
            }
        }
        s->samples += f->nb_samples;
        av_frame_unref(f);
    }
    av_frame_free(&f);
    if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) fail(ret, "drain sink");
}

static int run(const char *layout, const char *graph_text) {
    int64_t started = av_gettime_relative();
    AVChannelLayout channels = {0};
    check(av_channel_layout_from_string(&channels, layout), "channel layout");
    AVFilterGraph *g = avfilter_graph_alloc();
    if (!g) fail(AVERROR(ENOMEM), "filter graph");
    g->nb_threads = 1;
    AVFilterContext *source, *sink;
    char args[256];
    snprintf(args, sizeof(args), "time_base=1/%d:sample_rate=%d:sample_fmt=flt:channel_layout=%s", RATE, RATE, layout);
    check(avfilter_graph_create_filter(&source, avfilter_get_by_name("abuffer"), "input", args, NULL, g), "create source");
    sink = avfilter_graph_alloc_filter(g, avfilter_get_by_name("abuffersink"), "output");
    if (!sink) fail(AVERROR(ENOMEM), "create sink");
    check(av_opt_set(sink, "sample_formats", "flt", AV_OPT_SEARCH_CHILDREN), "sink format");
    check(avfilter_init_str(sink, NULL), "initialize sink");
    AVFilterInOut *inputs = avfilter_inout_alloc(), *outputs = avfilter_inout_alloc();
    if (!inputs || !outputs) fail(AVERROR(ENOMEM), "graph endpoints");
    outputs->name = av_strdup("in"); outputs->filter_ctx = source; outputs->pad_idx = 0;
    inputs->name = av_strdup("out"); inputs->filter_ctx = sink; inputs->pad_idx = 0;
    check(avfilter_graph_parse_ptr(g, graph_text, &inputs, &outputs, NULL), "parse preset");
    avfilter_inout_free(&inputs); avfilter_inout_free(&outputs);
    check(avfilter_graph_config(g, NULL), "configure graph");
    Stats s = {.channels = channels.nb_channels};
    for (int pos = 0; pos < TOTAL; pos += BLOCK) {
        AVFrame *f = av_frame_alloc();
        if (!f) fail(AVERROR(ENOMEM), "input frame");
        f->format = AV_SAMPLE_FMT_FLT; f->sample_rate = RATE; f->pts = pos;
        f->nb_samples = TOTAL - pos < BLOCK ? TOTAL - pos : BLOCK;
        check(av_channel_layout_copy(&f->ch_layout, &channels), "copy layout");
        check(av_frame_get_buffer(f, 0), "input samples");
        float *data = (float *)f->data[0];
        for (int n = 0; n < f->nb_samples; ++n) {
            int t = pos + n;
            double amplitude = t < RATE * 2 ? 0.02 : t < RATE * 4 ? 0.8 : 0;
            for (int c = 0; c < channels.nb_channels; ++c) {
                double sample = amplitude * sin(2 * M_PI * 1000 * t / RATE);
                /* Simultaneous short high peak; linker mode must protect every channel. */
                if ((t >= RATE * 9 / 5 && t < RATE * 9 / 5 + 48) ||
                    (t >= RATE * 3 && t < RATE * 3 + 48)) sample = 0.98;
                data[n * channels.nb_channels + c] = sample;
            }
        }
        check(av_buffersrc_add_frame_flags(source, f, AV_BUFFERSRC_FLAG_KEEP_REF), "feed input");
        av_frame_free(&f);
        drain(sink, &s);
    }
    check(av_buffersrc_add_frame_flags(source, NULL, 0), "flush input");
    drain(sink, &s);
    const double input_quiet = 0.02 / sqrt(2.0), input_loud = 0.8 / sqrt(2.0);
    double quiet = sqrt(s.quiet_sum / s.quiet_n), loud = sqrt(s.loud_sum / s.loud_n);
    double quiet_gain = 20 * log10(quiet / input_quiet);
    double loud_gain = 20 * log10(loud / input_loud);
    double gap_reduction = quiet_gain - loud_gain;
    int enabled = strcmp(graph_text, "anull") != 0;
    int ok = s.samples == TOTAL && s.quiet_n && s.loud_n;
    if (enabled) ok &= quiet_gain > 5.5 && quiet_gain < 6.5 && loud_gain < -5 && gap_reduction > 10 && s.peak <= 0.8913 && s.peak >= 0.85;
    else ok &= fabs(quiet_gain) < 0.01 && fabs(loud_gain) < 0.01 && fabs(s.peak - 0.98) < 0.001;
    double elapsed_ms = (av_gettime_relative() - started) / 1000.0;
    printf("{\"layout\":\"%s\",\"enabled\":%s,\"rate\":%d,\"channels\":%d,\"samples\":%ld,\"quiet_gain_db\":%.4f,\"loud_gain_db\":%.4f,\"gap_reduction_db\":%.4f,\"peak\":%.8f,\"elapsed_ms\":%.3f,\"pass\":%s}\n",
           layout, enabled ? "true" : "false", RATE, s.channels, s.samples, quiet_gain, loud_gain, gap_reduction, s.peak, elapsed_ms, ok ? "true" : "false");
    avfilter_graph_free(&g);
    av_channel_layout_uninit(&channels);
    return ok;
}

int main(int argc, char **argv) {
    const char *preset = argc > 1 ? argv[1] : PRESET;
    const char *layouts[] = {"stereo", "5.1", "7.1"};
    int ok = 1;
    printf("{\"avfilter_version\":%u,\"preset\":\"%s\"}\n", avfilter_version(), preset);
    for (unsigned i = 0; i < sizeof(layouts) / sizeof(layouts[0]); ++i) {
        ok &= run(layouts[i], "anull");
        ok &= run(layouts[i], preset);
    }
    return ok ? 0 : 1;
}
