package com.qinghaipao.autorun.desktop;

import com.qinghaipao.autorun.core.MapRepo;
import com.qinghaipao.autorun.core.Router;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Desktop counterpart of {@code AndroidAssets}: reads the same relative paths
 * ({@code maps/x.json}, {@code web/index.html}) out of a directory instead of the
 * APK's asset store.
 */
final class FileAssets implements MapRepo.MapSource, Router.WebAssets {

    private final File root;

    FileAssets(File root) {
        this.root = root;
    }

    File root() {
        return root;
    }

    @Override
    public InputStream open(String path) throws IOException {
        File f = new File(root, path);
        if (!f.isFile()) throw new FileNotFoundException(f.getAbsolutePath());
        return new FileInputStream(f);
    }
}
