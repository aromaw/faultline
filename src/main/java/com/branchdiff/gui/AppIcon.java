package com.branchdiff.gui;

import javax.swing.JFrame;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

/**
 * The faultline app icon (assets/icon/faultline.svg, rendered to PNGs by assets/icon/render.py): window icons in
 * several sizes, and the Dock / taskbar icon where {@link Taskbar} supports it (macOS).
 */
final class AppIcon {
    static final int[] SIZES = {16, 32, 48, 64, 128, 256, 512};
    private static List<Image> images;

    private AppIcon() {
    }

    static synchronized List<Image> images() {
        if (images != null) return images;
        List<Image> res = new ArrayList<>();
        for (int s : SIZES) {
            Image i = load(s);
            if (i != null) res.add(i);
        }
        images = List.copyOf(res);
        return images;
    }

    /** decoded lazily by the toolkit (no ImageIO on the start-up path); null when the resource is missing */
    static Image load(int size) {
        java.net.URL u = AppIcon.class.getResource("icons/faultline-" + size + ".png");
        return u == null ? null : Toolkit.getDefaultToolkit().createImage(u);
    }

    /** window icons, plus the Dock icon on macOS (ignored where unsupported) */
    static void install(JFrame frame) {
        List<Image> imgs = images();
        if (imgs.isEmpty()) return;
        // the window peer decodes every image it gets; the 512 px one is for the Dock only
        frame.setIconImages(imgs.size() > 1 ? imgs.subList(0, imgs.size() - 1) : imgs);
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(imgs.get(imgs.size() - 1));
            }
        } catch (RuntimeException | Error ignored) {
            // no taskbar on this platform / headless
        }
    }
}
