package org.jdownloader.extensions.eventscripter.sandboxobjects;

import java.io.File;
import java.io.IOException;

import org.appwork.utils.Files;
import org.appwork.utils.IO;
import org.jdownloader.extensions.eventscripter.EnvironmentException;

import jd.controlling.downloadcontroller.DownloadWatchDog;
import jd.plugins.LinkInfo;

public class FilePathSandbox {
    protected final File file;

    public FilePathSandbox(String fileOrUrl) {
        this(new File(fileOrUrl));
    }

    protected FilePathSandbox(File file) {
        this.file = file;
    }

    @Override
    public int hashCode() {
        if (file != null) {
            return file.hashCode();
        } else {
            return super.hashCode();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof FilePathSandbox) {
            final File fA = ((FilePathSandbox) obj).file;
            final File fB = this.file;
            return fA == fB || (fA != null && fA.equals(fB));
        } else {
            return super.equals(obj);
        }
    }

    public boolean isFile() throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("check if a filepath is a file");
        final boolean ret = file.isFile();
        return ret;
    }

    public boolean isDirectory() throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("check if a filepath is a directory");
        final boolean ret = file.isDirectory();
        return ret;
    }

    public boolean exists() throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("check if a filepath exists");
        final boolean ret = file.exists();
        return ret;
    }

    public boolean mkdirs() throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("create folders");
        final boolean ret = file.mkdirs();
        return ret;
    }

    public long getModifiedDate() {
        final long ret = file.lastModified();
        return ret;
    }

    public long getCreatedDate() {
        return -1;
    }

    public FilePathSandbox getParent() {
        return newFilePathSandbox(file.getParentFile());
    }

    protected FilePathSandbox newFilePathSandbox(final File file) {
        return new FilePathSandbox(file);
    }

    public FilePathSandbox[] getChildren() {
        final File[] files = file.listFiles();
        final FilePathSandbox[] ret;
        if (files == null || files.length == 0) {
            ret = new FilePathSandbox[0];
        } else {
            ret = new FilePathSandbox[files.length];
            for (int i = 0; i < files.length; i++) {
                ret[i] = newFilePathSandbox(files[i].getAbsoluteFile());
            }
        }
        return ret;
    }

    public String getPath() {
        return file.getPath();
    }

    public boolean renameTo(String to) throws EnvironmentException {
        return rename(to) != null;
    }

    public FilePathSandbox renameName(final String newName) throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("rename file or folder name only");
        final File dst = new File(file.getParentFile(), newName);
        if (file.renameTo(dst)) {
            return newFilePathSandbox(dst);
        } else {
            return null;
        }
    }

    public FilePathSandbox rename(final String newDest) throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("rename file or folder");
        final File dst = new File(newDest);
        if (file.renameTo(dst)) {
            return newFilePathSandbox(dst);
        } else {
            return null;
        }
    }

    public FilePathSandbox renamePath(final String newPath) throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("rename file or folder path only");
        final File dst = new File(newPath, getName());
        if (file.renameTo(dst)) {
            return newFilePathSandbox(dst);
        } else {
            return null;
        }
    }

    public boolean moveTo(String folder) throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("move a file to a new folder");
        File dest = new File(folder);
        if (!dest.exists()) {
            newFilePathSandbox(dest).mkdirs();
        }
        dest = new File(dest, file.getName());
        final boolean ret = file.renameTo(dest);
        return ret;
    }

    public String getPathSeparator() {
        return File.separator;
    }

    public long getFreeDiskSpace() {
        final File file = getFile();
        if (file == null) {
            return 0;
        } else {
            return Files.getUsableSpace(file);
        }
    }

    public long getReservedDiskSpace() {
        final File file = getFile();
        if (file == null) {
            return 0;
        } else {
            return DownloadWatchDog.getInstance().getSession().getDiskSpaceManager().getReservedDiskSpace(file, this);
        }
    }

    public LinkInfoSandbox getLinkInfo() {
        final File file = getFile();
        if (file == null) {
            return null;
        } else {
            final LinkInfo info = LinkInfo.getLinkInfo(file);
            if (info != null) {
                return new LinkInfoSandbox(info);
            } else {
                return null;
            }
        }
    }

    public boolean copyTo(String destDirectory, String destName, boolean overwrite) throws EnvironmentException {
        if (isFile()) {
            final File dest;
            if (destDirectory != null && destName != null) {
                org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("copy a file to a new file");
                dest = new File(new File(destDirectory), destName);
            } else if (destDirectory == null && destName != null) {
                org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("copy a file to a new file");
                dest = new File(getFile().getParentFile(), destName);
            } else if (destDirectory != null && destName == null) {
                org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("copy a file to a new folder");
                dest = new File(new File(destDirectory), getFile().getName());
            } else {
                return false;
            }
            if (dest.exists() && (overwrite == false || !dest.delete())) {
                return false;
            }
            if (!dest.getParentFile().exists()) {
                dest.getParentFile().mkdirs();
            }
            try {
                IO.copyFile(file, dest);
                return true;
            } catch (final IOException e) {
                throw new EnvironmentException(e);
            }
        }
        return false;
    }

    public boolean copyTo(String folder) throws EnvironmentException {
        if (isFile()) {
            org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("copy a file to a new folder");
            File dest = new File(folder);
            if (!dest.exists()) {
                dest.mkdirs();
            }
            dest = new File(dest, file.getName());
            try {
                if (!dest.exists()) {
                    IO.copyFile(file, dest);
                    return true;
                }
            } catch (final IOException e) {
                throw new EnvironmentException(e);
            }
        }
        return false;
    }

    public String getAbsolutePath() {
        return file.getAbsolutePath();
    }

    protected File getFile() {
        return file;
    }

    @Override
    public String toString() {
        return getPath();
    }

    public boolean delete() throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("delete a file or folder");
        return file.delete();
    }

    public boolean deleteRecursive() throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("delete a file or folder RECURSIVE");
        try {
            Files.deleteRecursiv(file);
        } catch (IOException e) {
            throw new EnvironmentException(e);
        }
        return file.exists();
    }

    public String getName() {
        return file.getName();
    }

    public String getExtension() {
        return org.appwork.utils.Files.getExtension(file.getName());
    }

    public long getSize() throws EnvironmentException {
        org.jdownloader.extensions.eventscripter.sandboxobjects.ScriptEnvironment.askForPermission("get the size of a file");
        return file.length();
    }
}
