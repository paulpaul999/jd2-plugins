//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org  http://jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
package org.jdownloader.startup;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Type;

import jd.gui.swing.laf.LookAndFeelController;

import org.appwork.loggingv3.LogV3;
import org.appwork.storage.JSonStorage;
import org.appwork.storage.JsonSerializer;
import org.appwork.storage.SimpleMapper;
import org.appwork.storage.TypeRef;
import org.appwork.storage.config.JsonConfig;
import org.appwork.storage.simplejson.JSonFactory;
import org.appwork.txtresource.TranslationFactory;
import org.appwork.utils.Application;
import org.appwork.utils.DebugMode;
import org.appwork.utils.IO;
import org.appwork.utils.IO.SYNC;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.logging2.LogSourceRedirector;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.swing.dialog.Dialog;
import org.jdownloader.extensions.ExtensionController;
import org.jdownloader.myjdownloader.client.json.JSonHandler;
import org.jdownloader.myjdownloader.client.json.JsonFactoryInterface;
import org.jdownloader.myjdownloader.client.json.MyJDJsonMapper;

public class Main {
    public static ParameterHandler PARAMETER_HANDLER = null;
    static {
        if (System.getProperty("org.appwork.LoggerFactory") == null) {
            System.setProperty("org.appwork.LoggerFactory", LogSourceRedirector.class.getName());
        }
        if (System.getProperty("java.net.preferIPv6Addresses") == null) {
            // https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/doc-files/net-properties.html
            System.setProperty("java.net.preferIPv6Addresses", "system");
        }
        org.appwork.utils.Application.setApplication(".jd_home");
        final String root = org.appwork.utils.Application.getRoot(jd.SecondLevelLaunch.class);
        LogV3.info("Application Root: " + root);// DO NOT REMOVE! this is important to have LogSystem initialized first!
        /**
         * The sorting algorithm used by java.util.Arrays.sort and (indirectly) by java.util.Collections.sort has been replaced. The new
         * sort implementation may throw an IllegalArgumentException if it detects a Comparable that violates the Comparable contract. The
         * previous implementation silently ignored such a situation. If the previous behavior is desired, you can use the new system
         * property, java.util.Arrays.useLegacyMergeSort, to restore previous mergesort behavior. Nature of Incompatibility: behavioral RFE:
         * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6804124
         *
         * Sorting live data (values changing during sorting) violates the general contract
         *
         * java.lang.IllegalArgumentException: Comparison method violates its general contract!
         */
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        try {
            /*
             * never cache negative answers,workaround for buggy dns servers that can fail and then the cache would be polluted for cache
             * timeout
             */
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", 0 + "");
        } catch (final Throwable e) {
        }
        try {
            copySVNtoHome();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        Dialog.getInstance().setLafManager(LookAndFeelController.getInstance());
    }

    public static void checkLanguageSwitch(final String[] args) {
        try {
            final File language = Application.getResource("cfg/language.json");
            String lng = null;
            if (language.isFile()) {
                lng = JSonStorage.restoreFrom(language, true, JSonStorage.KEY, TypeRef.STRING, TranslationFactory.getDesiredLanguage());
            }
            if (StringUtils.isEmpty(lng)) {
                lng = TranslationFactory.getDesiredLanguage();
            }
            TranslationFactory.setDesiredLanguage(lng);
            for (int i = 0; i < args.length; i++) {
                if (args[i].equalsIgnoreCase("-translatortest")) {
                    TranslationFactory.setDesiredLanguage(args[i + 1]);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void copySVNtoHome() {
        try {
            if (!Application.isJared(null) && Application.getRessourceURL("org/jdownloader/update/JDUpdateClient.class") == null || System.getProperty("copysvn") != null) {
                File workspace = new File(Main.class.getResource("/").toURI()).getParentFile();
                if (workspace.getName().equals("JDownloaderUpdater")) {
                    workspace = new File(workspace.getParentFile(), "JDownloader");
                }
                File svnEntriesFile = new File(workspace, ".svn/entries");
                if (svnEntriesFile.exists()) {
                    long lastMod = svnEntriesFile.lastModified();
                    try {
                        lastMod = Long.parseLong(Regex.getLines(IO.readFileToString(svnEntriesFile))[3].trim());
                    } catch (Throwable e) {
                    }
                    long lastUpdate = -1;
                    File lastSvnUpdateFile = Application.getResource("dev/lastSvnUpdate");
                    if (lastSvnUpdateFile.exists()) {
                        try {
                            lastUpdate = Long.parseLong(IO.readFileToString(lastSvnUpdateFile));
                        } catch (Throwable e) {
                        }
                    }
                    if (lastMod > lastUpdate) {
                        copyResource(workspace, "themes/themes", "themes");
                        copyResource(workspace, "ressourcen/jd", "jd");
                        copyResource(workspace, "ressourcen/tools", "tools");
                        copyResource(workspace, "translations/translations", "translations");
                        File jdJar = Application.getResource("JDownloader.jar");
                        jdJar.delete();
                        IO.copyFile(new File(workspace, "dev/JDownloader.jar"), jdJar);
                        lastSvnUpdateFile.delete();
                        lastSvnUpdateFile.getParentFile().mkdirs();
                        IO.writeStringToFile(lastSvnUpdateFile, lastMod + "");
                    }
                }
                // URL mainClass = Application.getRessourceURL("org", true);
                //
                // File svnJar = new File(new File(mainClass.toURI()).getParentFile().getParentFile(), "dev/JDownloader.jar");
                // FileCreationManager.getInstance().delete(jdjar, null);
                // IO.copyFile(svnJar, jdjar);
                //
                // }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void copyResource(File workspace, String from, String to) throws IOException {
        System.out.println("Copy SVN Resources " + new File(workspace, from) + " to " + Application.getResource(to));
        IO.copyFolderRecursive(new File(workspace, from), Application.getResource(to), true, new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                if (pathname.getAbsolutePath().contains(".svn")) {
                    return false;
                } else {
                    System.out.println("Copy " + pathname);
                    return true;
                }
            }
        }, SYNC.NONE);
    }

    public static void main(String[] args) {
        loadJXBrowser(Main.class.getClassLoader());
        // add Serializer to Handle JsonFactoryInterface from MyJDownloaderCLient Project
        JSonStorage.getMapper().putSerializer(JsonFactoryInterface.class, new JsonSerializer() {
            @Override
            public String toJSonString(Object object, Object mapper) {
                return ((JsonFactoryInterface) object).toJsonString();
            }
        });
        MyJDJsonMapper.HANDLER = new JSonHandler<Type>() {
            // set MyJDownloaderCLient JsonHandler
            final SimpleMapper mapper = new SimpleMapper() {
                @Override
                protected JSonFactory newJsonFactory(String jsonString) {
                    return new JSonFactory(jsonString) {
                        @Override
                        protected String dedupeString(String string) {
                            return string;
                        }
                    };
                }

                @Override
                protected void initMapper() {
                    super.initMapper();
                    putSerializer(JsonFactoryInterface.class, new JsonSerializer() {
                        @Override
                        public String toJSonString(Object object, Object mapper) {
                            return ((JsonFactoryInterface) object).toJsonString();
                        }
                    });
                }

                @Override
                public boolean isPrettyPrintEnabled() {
                    return false;
                }
            };

            @Override
            public String objectToJSon(Object payload) {
                return mapper.objectToString(payload);
            }

            @Override
            public <T> T jsonToObject(String dec, final Type clazz) {
                if (dec == null || "".equals(dec)) {
                    return null;
                } else {
                    return mapper.stringToObject(dec, new TypeRef<T>(clazz) {
                    });
                }
            }
        };
        checkLanguageSwitch(args);
        try {
            /* set D3D Property if not already set by user */
            if (CrossSystem.isWindows() && System.getProperty("sun.java2d.d3d") == null) {
                if (JsonConfig.create(org.jdownloader.settings.GraphicalUserInterfaceSettings.class).isUseD3D()) {
                    System.setProperty("sun.java2d.d3d", "true");
                } else {
                    System.setProperty("sun.java2d.d3d", "false");
                    // 4455041 - Even when ddraw is disabled, ddraw.dll is loaded when
                    // pixel format calls are made.
                    System.setProperty("sun.awt.nopixfmt", "true");
                }
            }
        } catch (final Throwable e) {
            e.printStackTrace();
        }
        PARAMETER_HANDLER = new ParameterHandler();
        PARAMETER_HANDLER.onStartup(args);
        // Rescan plugincached if required
        ExtensionController.getInstance().invalidateCacheIfRequired();
        jd.SecondLevelLaunch.mainStart(args);
    }

    public static void loadJXBrowser(ClassLoader cl) {
        try {
            if (DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                final File lib;
                switch (CrossSystem.getOSFamily()) {
                case LINUX:
                    if (Application.is64BitJvm()) {
                        lib = Application.getResource("libs/jxbrowser/jxbrowser-linux64.jar");
                    } else {
                        lib = Application.getResource("libs/jxbrowser/jxbrowser-linux32.jar");
                    }
                    break;
                case WINDOWS:
                    lib = Application.getResource("libs/jxbrowser/jxbrowser-win.jar");
                    break;
                case MAC:
                    lib = Application.getResource("libs/jxbrowser/jxbrowser-mac.jar");
                    break;
                default:
                    lib = null;
                }
                final File jar = Application.getResource("libs/jxbrowser/license.jar");
                if (jar.isFile()) {
                    Application.addUrlToClassPath(jar.toURI().toURL(), cl);
                    if (lib != null && lib.isFile()) {
                        Application.addUrlToClassPath(lib.toURI().toURL(), cl);
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}