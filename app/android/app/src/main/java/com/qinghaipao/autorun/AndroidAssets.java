package com.qinghaipao.autorun;

import android.content.res.AssetManager;

import com.qinghaipao.autorun.core.MapRepo;
import com.qinghaipao.autorun.core.Router;

import java.io.IOException;
import java.io.InputStream;

/**
 * Adapter: exposes the APK's assets to the engine through the two interfaces it
 * expects. Paths are relative to the assets root ({@code maps/x.json},
 * {@code web/index.html}), which is exactly how the engine asks for them.
 *
 * <p>The desktop runner has a mirror of this class reading a directory, which is
 * the only difference between the two hosts.
 */
final class AndroidAssets implements MapRepo.MapSource, Router.WebAssets {

    private final AssetManager assets;

    AndroidAssets(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public InputStream open(String path) throws IOException {
        return assets.open(path);
    }
}
