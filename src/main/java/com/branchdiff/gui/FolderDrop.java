package com.branchdiff.gui;

import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** a folder (or a file, which opens its folder) dropped onto the window opens as the project */
final class FolderDrop extends TransferHandler {
    private final Consumer<Path> open;

    FolderDrop(Consumer<Path> open) {
        this.open = open;
    }

    @Override
    public boolean canImport(TransferSupport s) {
        return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    @Override
    public boolean importData(TransferSupport s) {
        if (!canImport(s)) return false;
        try {
            @SuppressWarnings("unchecked")
            List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
            if (files.isEmpty()) return false;
            File f = files.get(0);
            File dir = f.isDirectory() ? f : f.getParentFile();
            if (dir == null) return false;
            open.accept(dir.toPath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
