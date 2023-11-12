package org.jdownloader.plugins.controller;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.ClosedByInterruptException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jd.plugins.Plugin;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;

import org.appwork.utils.Application;
import org.appwork.utils.Exceptions;
import org.appwork.utils.IO;
import org.appwork.utils.formatter.HexFormatter;
import org.appwork.utils.logging2.LogInterface;
import org.jdownloader.logging.LogController;
import org.jdownloader.plugins.controller.crawler.LazyCrawlerPlugin;
import org.jdownloader.plugins.controller.host.LazyHostPlugin;
import org.jdownloader.updatev2.ClassLoaderExtension;

public class PluginClassLoader extends URLClassLoader {
    private static final HashMap<String, HashMap<String, Object>> sharedPluginObjectsPool     = new HashMap<String, HashMap<String, Object>>();
    private static final WeakHashMap<Object, String>              sharedPluginObjectsPoolLock = new WeakHashMap<Object, String>();              ;
    // http://docs.oracle.com/javase/7/docs/technotes/guides/lang/cl-mt.html
    private static final HashSet<String>                          immutableClasses            = new HashSet<String>() {
        /**
         *
         */
        private static final long serialVersionUID = -5337264051307565501L;
        {
            add("java.lang.Boolean");
            add("java.lang.Byte");
            add("java.lang.String");
            add("java.lang.Double");
            add("java.lang.Integer");
            add("java.lang.Long");
            add("java.lang.Float");
            add("java.lang.Short");
            add("java.math.BigInteger");
            add("java.math.BigDecimal");
        }
    };

    private static class PluginClassLoaderClass {
        private final WeakReference<Class<?>> clazz;
        private final AtomicBoolean           initialized = new AtomicBoolean(false);

        private PluginClassLoaderClass(Class<?> clazz) {
            this.clazz = new WeakReference<Class<?>>(clazz);
        }

        private Class<?> getClazz() {
            return clazz.get();
        }
    }

    public static class PluginClassLoaderChild extends URLClassLoader {
        static {
            if (Application.getJavaVersion() >= Application.JAVA17) {
                try {
                    ClassLoader.registerAsParallelCapable();
                } catch (final Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        private static final byte _0XCA           = (byte) 0xca;
        private static final byte _0XFE           = (byte) 0xfe;
        private static final byte _0XBA           = (byte) 0xba;
        private static final byte _0XBE           = (byte) 0xbe;
        private boolean           createDummyLibs = true;
        private boolean           mapStaticFields = true;

        public boolean isMapStaticFields() {
            return mapStaticFields;
        }

        public void setMapStaticFields(boolean mapStaticFields) {
            this.mapStaticFields = mapStaticFields;
        }

        private boolean jared       = Application.isJared(PluginClassLoader.class);
        private String  pluginClass = null;

        // private final String creationHistory = Exceptions.getStackTrace(new Throwable());
        public PluginClassLoaderChild(PluginClassLoader parent) {
            super(new URL[] { Application.getRootUrlByClass(jd.SecondLevelLaunch.class, null) }, parent);
        }

        /**
         * @return the pluginClass
         */
        public String getPluginClass() {
            return pluginClass;
        }

        /**
         * @return the createDummyLibs
         */
        public boolean isCreateDummyLibs() {
            return createDummyLibs;
        }

        public boolean isUpdateRequired(String name) {
            if (!jared) {
                return false;
            }
            name = name.replace("/", ".");
            synchronized (DYNAMIC_LOADABLE_LOBRARIES) {
                Iterator<Entry<String, String>> it = DYNAMIC_LOADABLE_LOBRARIES.entrySet().iterator();
                while (it.hasNext()) {
                    Entry<String, String> next = it.next();
                    String dynamicPackage = next.getKey();
                    String libFile = next.getValue();
                    if (name.startsWith(dynamicPackage)) {
                        /* dynamic Library in use */
                        /* check if the library is already available on disk */
                        File lib = Application.getResource("libs/" + libFile);
                        if (lib.exists() && lib.isFile() && lib.length() != 0) {
                            /* file already exists on disk, so we can use it */
                            it.remove();
                            break;
                        } else if (lib.exists() && lib.isFile() && lib.length() == 0) {
                            /* dummy library, we have to wait for update */
                            return true;
                        } else if (!lib.exists()) {
                            /* library file not existing, create a new one if wished, so the update system replaces it with correct one */
                            return true;
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        private LogInterface getLogger(LogInterface logger) {
            if (logger != null) {
                return logger;
            } else {
                final LogInterface ret = LogController.getRebirthLogger(org.appwork.utils.logging2.extmanager.LoggerFactory.getDefaultLogger());
                if (ret == null) {
                    return LogController.CL(false);
                } else {
                    return ret;
                }
            }
        }

        // class loading must NOT be interrupted! may result in NoClassDefFoundError which requires a restart
        private Class<?> loadAndDefineClass(final URL myUrl, final String name) throws ClassFormatError, IOException {
            int retryCounter = 5;
            int loopCounter = 0;
            boolean interruptFlag = false;
            LogInterface logger = null;
            try {
                while (true) {
                    loopCounter++;
                    byte data[] = null;
                    try {
                        data = IO.readURL(myUrl);
                        try {
                            if (_0XCA != data[0] || _0XFE != data[1] || _0XBA != data[2] || _0XBE != data[3]) {
                                final String id = "classloader_" + HexFormatter.byteArrayToHex(new byte[] { data[0], data[1], data[2], data[3] });
                                final String clExtension = System.getProperty(id);
                                data = ((ClassLoaderExtension) Class.forName(clExtension).newInstance()).run(data);
                            }
                        } catch (Throwable e) {
                            throw new ClassFormatError("No Class File");
                        }
                        if (data == null || data.length == 0) {
                            throw new ClassFormatError("No Class File");
                        }
                        return defineClass(name, data, 0, data.length);
                    } catch (ClassFormatError e) {
                        logger = getLogger(logger);
                        if (data != null) {
                            logger.severe("loop:" + loopCounter + "|retry:" + retryCounter + "|ClassFormatError:class=" + name + "|file=" + myUrl + "|size=" + data.length);
                        } else {
                            logger.severe("loop:" + loopCounter + "|retry:" + retryCounter + "|ClassFormatError:class=" + name + "|file=" + myUrl);
                        }
                        logger.log(e);
                        if (--retryCounter == 0) {
                            throw e;
                        } else {
                            try {
                                Thread.sleep(150);
                            } catch (final InterruptedException ie) {
                                interruptFlag = true;
                            }
                        }
                    } catch (ClosedByInterruptException e) {
                        interruptFlag = true;
                        Thread.interrupted();
                        logger = getLogger(logger);
                        logger.severe("loop:" + loopCounter + "|retry:" + retryCounter + "|ClosedByInterruptException:class=" + name + "|file=" + myUrl);
                        logger.log(e);
                    } catch (IOException e) {
                        logger = getLogger(logger);
                        logger.severe("loop:" + loopCounter + "|retry:" + retryCounter + "|IOException:class=" + name + "|file=" + myUrl);
                        logger.log(e);
                        if (--retryCounter == 0) {
                            throw e;
                        } else {
                            try {
                                Thread.sleep(150);
                            } catch (final InterruptedException ie) {
                                interruptFlag = true;
                            }
                        }
                    }
                }
            } finally {
                if (interruptFlag) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private final Object getClassLock(final Class<?> clazz) {
            final String clazzID = clazz.getName();
            synchronized (sharedPluginObjectsPoolLock) {
                final Iterator<Entry<Object, String>> it = sharedPluginObjectsPoolLock.entrySet().iterator();
                while (it.hasNext()) {
                    final Entry<Object, String> next = it.next();
                    if (next.getValue().equals(clazzID)) {
                        return next.getKey();
                    }
                }
                final Object lock = new Object();
                sharedPluginObjectsPoolLock.put(lock, new String(clazzID));
                return lock;
            }
        }

        private final HashMap<String, Object> getSharedPluginObjects(final Class<?> currentClass) {
            synchronized (sharedPluginObjectsPool) {
                return sharedPluginObjectsPool.get(currentClass.getName());
            }
        }

        private final void setSharedPluginObjects(final Class<?> currentClass, final boolean sharedPluginsObjectsExisting, final HashMap<String, Object> sharedPluginObjects) {
            synchronized (sharedPluginObjectsPool) {
                if (sharedPluginObjects != null && sharedPluginObjects.size() == 0) {
                    sharedPluginObjectsPool.remove(currentClass.getName());
                } else if (!sharedPluginsObjectsExisting && sharedPluginObjects != null && sharedPluginObjects.size() > 0) {
                    sharedPluginObjectsPool.put(new String(currentClass.getName()), sharedPluginObjects);
                }
            }
        }

        private final Class<?> mapStaticFields(final Class<?> currentClass) {
            if (isMapStaticFields() && currentClass != null && currentClass.getClassLoader() instanceof PluginClassLoaderChild) {
                final String currentClassName = currentClass.getName();
                LogInterface logger = null;
                final Object lock = getClassLock(currentClass);
                try {
                    final Field[] fields = currentClass.getDeclaredFields();
                    synchronized (lock) {
                        HashMap<String, Object> sharedPluginObjects = getSharedPluginObjects(currentClass);
                        final boolean sharedPluginsObjectsExisting = sharedPluginObjects != null;
                        try {
                            final HashSet<String> knownFields = new HashSet<String>();
                            if (sharedPluginObjects != null) {
                                knownFields.addAll(sharedPluginObjects.keySet());
                            }
                            for (Field field : fields) {
                                final String fieldName = field.getName();
                                if (!field.isSynthetic()) {
                                    final int modifiers = field.getModifiers();
                                    if (Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)) {
                                        if (field.getType().isEnum() || field.isEnumConstant()) {
                                            logger = getLogger(logger);
                                            logger.info("Class " + currentClassName + " has static enum: " + fieldName);
                                            continue;
                                        }
                                        if (field.getType().isPrimitive()) {
                                            logger = getLogger(logger);
                                            logger.info("Class " + currentClassName + " has static primitive field: " + fieldName);
                                            continue;
                                        }
                                        if (immutableClasses.contains(field.getType().getName())) {
                                            logger = getLogger(logger);
                                            logger.info("Class " + currentClassName + " has static immutable field: " + fieldName);
                                            continue;
                                        }
                                        /* we only share static objects */
                                        field.setAccessible(true);
                                        if (knownFields.contains(fieldName) && sharedPluginObjects != null) {
                                            final Object fieldObject = sharedPluginObjects.get(fieldName);
                                            try {
                                                field.set(null, fieldObject);
                                                knownFields.remove(fieldName);
                                                continue;
                                            } catch (final Throwable e) {
                                                logger = getLogger(logger);
                                                logger.severe("Cant modify Field " + fieldName + " for " + currentClassName);
                                            }
                                        }
                                        Object fieldObject = field.get(null);
                                        if (fieldObject != null) {
                                            if (fieldObject.getClass().getClassLoader() instanceof PluginClassLoaderChild) {
                                                logger = getLogger(logger);
                                                logger.info("FIXME!!!! Class " + currentClassName + " has customized static field: " + fieldName + "!");
                                            }
                                            if (sharedPluginObjects == null) {
                                                sharedPluginObjects = new HashMap<String, Object>();
                                            }
                                            sharedPluginObjects.put(new String(fieldName), fieldObject);// dereference from field
                                        } else {
                                            logger = getLogger(logger);
                                            logger.info("Class " + currentClassName + " has static field: " + fieldName + " with null content!");
                                        }
                                    }
                                }
                            }
                            for (final String missingField : knownFields) {
                                logger = getLogger(logger);
                                logger.info("Class " + currentClassName + " no longer has static field: " + missingField);
                                if (sharedPluginObjects != null) {
                                    sharedPluginObjects.remove(missingField);
                                }
                            }
                        } finally {
                            setSharedPluginObjects(currentClass, sharedPluginsObjectsExisting, sharedPluginObjects);
                        }
                    }
                } catch (final Throwable e) {
                    logger = getLogger(logger);
                    logger.info("Throwable in Class " + currentClassName);
                    logger.log(e);
                }
            }
            return currentClass;
        }

        private final HashMap<String, PluginClassLoaderClass> LOADEDCLASSES = new HashMap<String, PluginClassLoaderClass>();

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            return super.loadClass(name, resolve);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            try {
                if (jared) {
                    synchronized (DYNAMIC_LOADABLE_LOBRARIES) {
                        Iterator<Entry<String, String>> it = DYNAMIC_LOADABLE_LOBRARIES.entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<String, String> next = it.next();
                            String dynamicPackage = next.getKey();
                            String libFile = next.getValue();
                            if (name.startsWith(dynamicPackage)) {
                                /* dynamic Library in use */
                                /* check if the library is already available on disk */
                                File lib = Application.getResource("libs/" + libFile);
                                if (lib.exists() && lib.isFile() && lib.length() != 0) {
                                    /* file already exists on disk, so we can use it */
                                    it.remove();
                                    break;
                                } else if (lib.exists() && lib.isFile() && lib.length() == 0) {
                                    /* dummy library, we have to wait for update */
                                    throw new UpdateRequiredClassNotFoundException(libFile);
                                } else if (!lib.exists()) {
                                    /*
                                     * library file not existing, create a new one if wished, so the update system replaces it with correct
                                     * one
                                     */
                                    if (createDummyLibs) {
                                        lib.createNewFile();
                                    }
                                    throw new UpdateRequiredClassNotFoundException(libFile);
                                }
                                throw new ClassNotFoundException(name);
                            }
                        }
                    }
                }
                if (!name.startsWith("jd.plugins.hoster") && !name.startsWith("jd.plugins.decrypter")) {
                    return super.loadClass(name);
                }
                if (name.startsWith("jd.plugins.hoster.RTMPDownload")) {
                    return super.loadClass(name);
                }
                PluginClassLoaderClass pCLc = null;
                final Class<?> clazz;
                synchronized (LOADEDCLASSES) {
                    pCLc = LOADEDCLASSES.get(name);
                    if (pCLc == null) {
                        final URL myUrl = Application.getRessourceURL(name.replace(".", "/") + ".class");
                        if (myUrl == null) {
                            throw new ClassNotFoundException("Class does not exist(anymore): " + name);
                        }
                        clazz = loadAndDefineClass(myUrl, name);
                        pCLc = new PluginClassLoaderClass(clazz);
                        LOADEDCLASSES.put(name, pCLc);
                    } else {
                        clazz = pCLc.getClazz();
                    }
                }
                if (pCLc.initialized.get() == false) {
                    mapStaticFields(clazz);
                    pCLc.initialized.set(true);
                }
                return clazz;
            } catch (Exception e) {
                final LogInterface logger = getLogger(null);
                logger.log(e);
                if (e instanceof UpdateRequiredClassNotFoundException) {
                    throw (UpdateRequiredClassNotFoundException) e;
                } else if (e instanceof ClassNotFoundException) {
                    throw (ClassNotFoundException) e;
                } else {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }

        /**
         * @param createDummyLibs
         *            the createDummyLibs to set
         */
        public void setCreateDummyLibs(boolean createDummyLibs) {
            this.createDummyLibs = createDummyLibs;
        }

        /**
         * @param pluginClass
         *            the pluginClass to set
         */
        public void setPluginClass(String pluginClass) {
            this.pluginClass = pluginClass;
        }
    }

    private static final WeakHashMap<PluginClassLoaderChild, WeakReference<LazyPlugin<? extends Plugin>>> sharedLazyPluginClassLoader  = new WeakHashMap<PluginClassLoaderChild, WeakReference<LazyPlugin<? extends Plugin>>>();
    private static final WeakHashMap<PluginClassLoaderChild, String>                                      sharedPluginClassLoader      = new WeakHashMap<PluginClassLoaderChild, String>();
    private static final WeakHashMap<Thread, WeakReference<PluginClassLoaderChild>>                       threadPluginClassLoader      = new WeakHashMap<Thread, WeakReference<PluginClassLoaderChild>>();
    private static final WeakHashMap<ThreadGroup, WeakReference<PluginClassLoaderChild>>                  threadGroupPluginClassLoader = new WeakHashMap<ThreadGroup, WeakReference<PluginClassLoaderChild>>();
    private static final PluginClassLoader                                                                INSTANCE                     = new PluginClassLoader();
    private static final HashMap<String, String>                                                          DYNAMIC_LOADABLE_LOBRARIES   = new HashMap<String, String>();
    static {
        synchronized (DYNAMIC_LOADABLE_LOBRARIES) {
            DYNAMIC_LOADABLE_LOBRARIES.put("org.bouncycastle", "bcprov-jdk15on.jar");
        }
    }

    private synchronized static PluginClassLoaderChild fetchSharedChild(final LazyPlugin<? extends Plugin> lazyPlugin, final PluginClassLoaderChild putIfAbsent) {
        final Iterator<Entry<PluginClassLoaderChild, WeakReference<LazyPlugin<? extends Plugin>>>> it = sharedLazyPluginClassLoader.entrySet().iterator();
        while (it.hasNext()) {
            final Entry<PluginClassLoaderChild, WeakReference<LazyPlugin<? extends Plugin>>> next = it.next();
            final WeakReference<LazyPlugin<? extends Plugin>> weakPlugin = next.getValue();
            if (weakPlugin != null) {
                final LazyPlugin<? extends Plugin> plugin = weakPlugin.get();
                if (plugin != null && (lazyPlugin == plugin || lazyPlugin.getClassName().equals(plugin.getClassName()) && lazyPlugin.getVersion() == plugin.getVersion() && Arrays.equals(lazyPlugin.getLazyPluginClass().getSha256(), plugin.getLazyPluginClass().getSha256()))) {
                    final PluginClassLoaderChild ret = next.getKey();
                    if (ret != null) {
                        return ret;
                    }
                    break;
                }
            }
        }
        if (putIfAbsent != null) {
            sharedLazyPluginClassLoader.put(putIfAbsent, new WeakReference<LazyPlugin<? extends Plugin>>(lazyPlugin));
            return putIfAbsent;
        }
        return null;
    }

    private synchronized static PluginClassLoaderChild fetchSharedChild(final Plugin plugin, final PluginClassLoaderChild putIfAbsent) {
        final Iterator<Entry<PluginClassLoaderChild, String>> it = sharedPluginClassLoader.entrySet().iterator();
        final String cacheID = getCacheID(plugin);
        while (it.hasNext()) {
            final Entry<PluginClassLoaderChild, String> next = it.next();
            final String ID = next.getValue();
            if (ID != null && ID.equals(cacheID)) {
                final PluginClassLoaderChild ret = next.getKey();
                if (ret != null) {
                    return ret;
                }
                break;
            }
        }
        if (putIfAbsent != null) {
            sharedPluginClassLoader.put(putIfAbsent, cacheID);
            return putIfAbsent;
        }
        return null;
    }

    private static String getCacheID(final Plugin plugin) {
        if (plugin instanceof PluginForHost) {
            final LazyHostPlugin lazyP = ((PluginForHost) plugin).getLazyP();
            return lazyP.getClassName() + lazyP.getVersion() + HexFormatter.byteArrayToHex(lazyP.getLazyPluginClass().getSha256());
        } else if (plugin instanceof PluginForDecrypt) {
            final LazyCrawlerPlugin lazyC = ((PluginForDecrypt) plugin).getLazyC();
            return lazyC.getClassName() + lazyC.getVersion() + HexFormatter.byteArrayToHex(lazyC.getLazyPluginClass().getSha256());
        }
        return null;
    }

    public static PluginClassLoader getInstance() {
        return INSTANCE;
    }

    public synchronized static PluginClassLoaderChild getSharedChild(LazyPlugin<? extends Plugin> lazyPlugin) {
        if (lazyPlugin == null) {
            return PluginClassLoader.getInstance().getChild();
        }
        PluginClassLoaderChild ret = fetchSharedChild(lazyPlugin, null);
        if (ret == null) {
            ret = PluginClassLoader.getInstance().getChild();
            return fetchSharedChild(lazyPlugin, ret);
        }
        return ret;
    }

    public synchronized static PluginClassLoaderChild getSharedChild(Plugin plugin) {
        if (plugin != null) {
            PluginClassLoaderChild ret = null;
            if (plugin instanceof PluginForHost) {
                ret = fetchSharedChild(((PluginForHost) plugin).getLazyP(), null);
            } else if (plugin instanceof PluginForDecrypt) {
                ret = fetchSharedChild(((PluginForDecrypt) plugin).getLazyC(), null);
            }
            if (ret == null) {
                ret = fetchSharedChild(plugin, null);
                if (ret == null) {
                    ret = fetchSharedChild(plugin, PluginClassLoader.getInstance().getChild());
                }
            }
            return ret;
        }
        return null;
    }

    public synchronized static PluginClassLoaderChild getThreadPluginClassLoaderChild() {
        final Thread currentThread = Thread.currentThread();
        threadPluginClassLoader.isEmpty();
        threadGroupPluginClassLoader.isEmpty();
        WeakReference<PluginClassLoaderChild> wcl = threadPluginClassLoader.get(currentThread);
        PluginClassLoaderChild cl = null;
        if (wcl != null && (cl = wcl.get()) != null) {
            return cl;
        }
        // TODO: rewrite to remove ThreadGroup dependency, JEP 425
        ThreadGroup threadGroup = currentThread.getThreadGroup();
        while (threadGroup != null) {
            wcl = threadGroupPluginClassLoader.get(threadGroup);
            if (wcl != null && (cl = wcl.get()) != null) {
                return cl;
            }
            threadGroup = threadGroup.getParent();
        }
        return null;
    }

    public synchronized static void setThreadPluginClassLoaderChild(PluginClassLoaderChild threadChild, PluginClassLoaderChild groupChild) {
        final Thread currentThread = Thread.currentThread();
        // TODO: rewrite to remove ThreadGroup dependency, JEP 425
        final ThreadGroup threadGroup = currentThread.getThreadGroup();
        threadPluginClassLoader.isEmpty();
        threadGroupPluginClassLoader.isEmpty();
        if (threadChild == null) {
            threadPluginClassLoader.remove(currentThread);
        } else {
            threadPluginClassLoader.put(currentThread, new WeakReference<PluginClassLoaderChild>(threadChild));
        }
        if (threadGroup != null) {
            if (groupChild == null) {
                threadGroupPluginClassLoader.remove(threadGroup);
            } else {
                threadGroupPluginClassLoader.put(threadGroup, new WeakReference<PluginClassLoaderChild>(groupChild));
            }
        }
    }

    private final String creationHistory = Exceptions.getStackTrace(new Throwable());

    private PluginClassLoader() {
        super(new URL[] { Application.getRootUrlByClass(jd.SecondLevelLaunch.class, null) }, PluginClassLoader.class.getClassLoader());
    }

    public PluginClassLoaderChild getChild() {
        return new PluginClassLoaderChild(this);
    }
}
