// JNI bridge: develop a DNG (or other raw) to 16-bit linear Rec.2020 via LibRaw.
//
// LibRaw applies the file's own colour matrix during demosaic, so requesting Rec.2020 output
// gives camera-gamut-correct linear light in a space the Kotlin pipeline already knows how to
// move into Apple Wide Gamut. Gamma is forced to 1.0 (linear) and auto-brightening is disabled
// so the values stay scene-referred; middle-grey placement is left to the app's exposure slider.

#include <jni.h>
#include <android/log.h>
#include <vector>
#include "libraw/libraw.h"

#define LOG_TAG "LuttyRaw"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_apyfz_lutty_media_RawDecoder_nativeDevelop(
        JNIEnv *env, jclass, jbyteArray data, jintArray outWH, jint maxEdge) {

    jsize len = env->GetArrayLength(data);
    jbyte *buf = env->GetByteArrayElements(data, nullptr);

    LibRaw proc;
    proc.imgdata.params.output_bps = 16;       // 16-bit output
    proc.imgdata.params.gamm[0] = 1.0;          // linear gamma (no tone curve)
    proc.imgdata.params.gamm[1] = 1.0;
    proc.imgdata.params.no_auto_bright = 1;     // keep scene-referred levels
    proc.imgdata.params.use_camera_wb = 1;      // as-shot white balance
    proc.imgdata.params.output_color = 8;       // Rec.2020 primaries
    proc.imgdata.params.highlight = 0;          // clip highlights (no recovery blending)
    proc.imgdata.params.user_flip = -1;         // honour the raw's orientation tag

    jshortArray result = nullptr;
    int rc = proc.open_buffer(reinterpret_cast<void *>(buf), len);
    if (rc != LIBRAW_SUCCESS) { LOGE("open_buffer: %s", libraw_strerror(rc)); goto done; }

    rc = proc.unpack();
    if (rc != LIBRAW_SUCCESS) { LOGE("unpack: %s", libraw_strerror(rc)); goto done; }

    rc = proc.dcraw_process();
    if (rc != LIBRAW_SUCCESS) { LOGE("process: %s", libraw_strerror(rc)); goto done; }

    {
        int err = 0;
        libraw_processed_image_t *img = proc.dcraw_make_mem_image(&err);
        if (!img) { LOGE("make_mem_image failed: %d", err); goto done; }
        if (img->type == LIBRAW_IMAGE_BITMAP && img->bits == 16 && img->colors == 3) {
            const int w = img->width, h = img->height;
            // Downsample here (nearest, integer step) so the Java array is bounded regardless of
            // sensor size. A 48 MP develop is ~292 MB as a full short[]; capping the long edge
            // keeps it small enough to allocate, and avoids ever moving the full frame to the heap.
            int step = 1;
            if (maxEdge > 0) {
                const int lo = w > h ? w : h;
                step = (lo + maxEdge - 1) / maxEdge;
                if (step < 1) step = 1;
            }
            const int ow = w / step, oh = h / step;
            const jsize count = (jsize) ow * oh * 3;
            const auto *src = reinterpret_cast<const jshort *>(img->data);
            result = env->NewShortArray(count);
            if (result) {
                if (step == 1) {
                    env->SetShortArrayRegion(result, 0, count, src);
                } else {
                    std::vector<jshort> tmp((size_t) count);
                    for (int y = 0; y < oh; y++) {
                        const int sy = y * step;
                        for (int x = 0; x < ow; x++) {
                            const int si = (sy * w + x * step) * 3;
                            const int di = (y * ow + x) * 3;
                            tmp[di] = src[si]; tmp[di + 1] = src[si + 1]; tmp[di + 2] = src[si + 2];
                        }
                    }
                    env->SetShortArrayRegion(result, 0, count, tmp.data());
                }
                jint wh[2] = { ow, oh };
                env->SetIntArrayRegion(outWH, 0, 2, wh);
            }
        } else {
            LOGE("unexpected image: type=%d bits=%d colors=%d", img->type, img->bits, img->colors);
        }
        LibRaw::dcraw_clear_mem(img);
    }

done:
    proc.recycle();
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return result;
}
