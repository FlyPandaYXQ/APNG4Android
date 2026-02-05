package com.github.penfeizhou.animation.glide;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.drawable.DrawableResource;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.github.penfeizhou.animation.apng.APNGDrawable;
import com.github.penfeizhou.animation.apng.decode.APNGDecoder;
import com.github.penfeizhou.animation.avif.AVIFDrawable;
import com.github.penfeizhou.animation.avif.decode.AVIFDecoder;
import com.github.penfeizhou.animation.decode.FrameSeqDecoder;
import com.github.penfeizhou.animation.gif.GifDrawable;
import com.github.penfeizhou.animation.gif.decode.GifDecoder;
import com.github.penfeizhou.animation.webp.WebPDrawable;
import com.github.penfeizhou.animation.webp.decode.WebPDecoder;

/**
 * @Description: com.github.penfeizhou.animation.glide
 * @Author: pengfei.zhou
 * @CreateDate: 2020/8/21
 */
class FrameDrawableTranscoder implements ResourceTranscoder<FrameSeqDecoder, Drawable> {

    @Nullable
    @Override
    public Resource<Drawable> transcode(@NonNull Resource<FrameSeqDecoder> toTranscode, @NonNull Options options) {
        FrameSeqDecoder frameSeqDecoder = toTranscode.get();
        boolean noMeasure = options.get(AnimationDecoderOption.NO_ANIMATION_BOUNDS_MEASURE);
        // 核心新增：从Glide Options中读取播放速度（默认1.0f）
        float animationSpeed = options.get(AnimationDecoderOption.ANIMATION_SPEED);

        if (frameSeqDecoder instanceof APNGDecoder) {
            final APNGDrawable apngDrawable = new APNGDrawable((APNGDecoder) frameSeqDecoder);
            apngDrawable.setAutoPlay(false);
            apngDrawable.setNoMeasure(noMeasure);
            apngDrawable.setSpeed(animationSpeed); // 为APNG设置播放速度
            return new DrawableResource<Drawable>(apngDrawable) {
                @NonNull
                @Override
                public Class<Drawable> getResourceClass() {
                    return Drawable.class;
                }

                @Override
                public int getSize() {
                    return apngDrawable.getMemorySize();
                }

                @Override
                public void recycle() {
                    apngDrawable.stop();
                }
            };
        } else if (frameSeqDecoder instanceof WebPDecoder) {
            final WebPDrawable webPDrawable = new WebPDrawable((WebPDecoder) frameSeqDecoder);
            webPDrawable.setAutoPlay(false);
            webPDrawable.setNoMeasure(noMeasure);
            webPDrawable.setSpeed(animationSpeed); // 为WebP设置播放速度
            return new DrawableResource<Drawable>(webPDrawable) {
                @NonNull
                @Override
                public Class<Drawable> getResourceClass() {
                    return Drawable.class;
                }

                @Override
                public int getSize() {
                    return webPDrawable.getMemorySize();
                }

                @Override
                public void recycle() {
                    webPDrawable.stop();
                }
            };
        } else if (frameSeqDecoder instanceof GifDecoder) {
            final GifDrawable gifDrawable = new GifDrawable((GifDecoder) frameSeqDecoder);
            gifDrawable.setAutoPlay(false);
            gifDrawable.setNoMeasure(noMeasure);
            gifDrawable.setSpeed(animationSpeed); // 为GIF设置播放速度
            return new DrawableResource<Drawable>(gifDrawable) {
                @NonNull
                @Override
                public Class<Drawable> getResourceClass() {
                    return Drawable.class;
                }

                @Override
                public int getSize() {
                    return gifDrawable.getMemorySize();
                }

                @Override
                public void recycle() {
                    gifDrawable.stop();
                }
            };
        } else if (frameSeqDecoder instanceof AVIFDecoder) {
            final AVIFDrawable avifDrawable = new AVIFDrawable((AVIFDecoder) frameSeqDecoder);
            avifDrawable.setAutoPlay(false);
            avifDrawable.setNoMeasure(noMeasure);
            avifDrawable.setSpeed(animationSpeed); // 为AVIF设置播放速度
            return new DrawableResource<Drawable>(avifDrawable) {
                @NonNull
                @Override
                public Class<Drawable> getResourceClass() {
                    return Drawable.class;
                }

                @Override
                public int getSize() {
                    return avifDrawable.getMemorySize();
                }

                @Override
                public void recycle() {
                    avifDrawable.stop();
                }
            };
        } else {
            return null;
        }
    }
}