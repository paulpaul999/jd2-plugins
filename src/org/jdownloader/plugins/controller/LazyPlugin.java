package org.jdownloader.plugins.controller;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.appwork.exceptions.WTFException;
import org.appwork.storage.config.MinTimeWeakReference;
import org.appwork.storage.config.MinTimeWeakReferenceCleanup;
import org.appwork.storage.config.annotations.LabelInterface;
import org.appwork.storage.config.annotations.TooltipInterface;
import org.appwork.utils.Application;
import org.appwork.utils.StringUtils;
import org.jdownloader.plugins.controller.PluginClassLoader.PluginClassLoaderChild;
import org.jdownloader.translate._JDT;

import jd.PluginWrapper;
import jd.config.Property;
import jd.plugins.Plugin;

public abstract class LazyPlugin<T extends Plugin> implements MinTimeWeakReferenceCleanup {
    public static enum FEATURE implements LabelInterface, TooltipInterface {
        IMAGE_GALLERY {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_IMAGE_GALLERY();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_IMAGE_GALLERY_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        IMAGE_HOST {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_IMAGE_HOST();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_IMAGE_HOST_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        AUDIO_STREAMING {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_AUDIO_STREAMING();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_AUDIO_STREAMING_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        VIDEO_STREAMING {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_VIDEO_STREAMING();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_VIDEO_STREAMING_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        USENET {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_USENET();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_USENET_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        MULTIHOST {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_MULTIHOST();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_MULTIHOST_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        PASTEBIN {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_PASTEBIN();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_PASTEBIN_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        XXX {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_XXX();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_XXX_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        GENERIC {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_GENERIC();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_GENERIC_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return true;
            }
        },
        FAVICON {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_FAVICON();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_FAVICON_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return true;
            }
        },
        INTERNAL {
            @Override
            public String getLabel() {
                return "INTERNAL";
            }

            @Override
            public String getTooltip() {
                return "INTERNAL";
            }

            @Override
            public boolean isInternal() {
                return true;
            }
        },
        ASSIGN_PLUGIN {
            @Override
            public String getLabel() {
                return "ASSIGN_PLUGIN";
            }

            @Override
            public String getTooltip() {
                return "ASSIGN_PLUGIN";
            }

            @Override
            public boolean isInternal() {
                return true;
            }
        },
        COOKIE_LOGIN_ONLY {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_COOKIE_LOGIN_ONLY();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_COOKIE_LOGIN_ONLY_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        COOKIE_LOGIN_OPTIONAL {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_COOKIE_LOGIN_OPTIONAL();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_COOKIE_LOGIN_OPTIONAL_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        API_KEY_LOGIN {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_API_KEY_LOGIN();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_API_KEY_LOGIN_TOOLTIP();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        },
        USERNAME_IS_EMAIL {
            @Override
            public String getLabel() {
                return _JDT.T.LazyHostPlugin_FEATURE_USERNAME_IS_EMAIL();
            }

            @Override
            public String getTooltip() {
                return _JDT.T.LazyHostPlugin_FEATURE_USERNAME_IS_EMAIL();
            }

            @Override
            public boolean isInternal() {
                return false;
            }
        };

        public static final long CACHEVERSION = Math.abs(StringUtils.join(values(), "<->").hashCode()) + Math.abs(StringUtils.join(values(), ":").hashCode()) + Math.abs(StringUtils.join(values(), "<=>").hashCode());

        public boolean isSet(FEATURE[] features) {
            if (features != null) {
                for (final FEATURE feature : features) {
                    if (this.equals(feature)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Return true if this is an internal feature. </br>
         * Internal features are used internally only and are not displayed in GUI.
         */
        public abstract boolean isInternal();
    }

    /**
     * returns true if LazyCrawlerPlugin has one matching feature
     *
     * @param features
     * @return
     */
    public boolean hasFeature(final FEATURE... features) {
        final FEATURE[] thisPluginFeatures = getFeatures();
        if (features != null && features.length > 0 && thisPluginFeatures != null && thisPluginFeatures.length > 0) {
            for (final FEATURE feature : features) {
                if (feature.isSet(thisPluginFeatures)) {
                    return true;
                }
            }
        }
        return false;
    }

    public FEATURE[] getFeatures() {
        return features;
    }

    protected void setFeatures(LazyPlugin.FEATURE[] features) {
        if (features == null || features.length == 0) {
            this.features = null;
        } else {
            this.features = features;
        }
    }

    private final static Charset                           UTF8            = Charset.forName("UTF-8");
    private final byte[]                                   patternBytes;
    private volatile MinTimeWeakReference<Pattern>         compiledPattern = null;
    private final String                                   displayName;
    protected volatile WeakReference<Class<T>>             pluginClass;
    protected volatile WeakReference<T>                    prototypeInstance;
    /* PluginClassLoaderChild used to load this Class */
    private volatile WeakReference<PluginClassLoaderChild> classLoader;
    private volatile MinTimeWeakReference<Matcher>         matcher         = null;
    private FEATURE[]                                      features        = null;

    public PluginWrapper getPluginWrapper() {
        return new PluginWrapper(this) {
            /* workaround for old plugin system */
        };
    }

    private final LazyPluginClass lazyPluginClass;

    public final LazyPluginClass getLazyPluginClass() {
        return lazyPluginClass;
    }

    public LazyPlugin(LazyPluginClass lazyPluginClass, String patternString, String displayName, Class<T> class1, PluginClassLoaderChild classLoader) {
        this.patternBytes = patternString.getBytes(UTF8);
        this.lazyPluginClass = lazyPluginClass;
        if (class1 != null) {
            pluginClass = new WeakReference<Class<T>>(class1);
        }
        this.displayName = Property.dedupeString(displayName.toLowerCase(Locale.ENGLISH));
        if (classLoader != null) {
            this.classLoader = new WeakReference<PluginClassLoaderChild>(classLoader);
        }
    }

    public String getID() {
        return getClassName() + "/" + getDisplayName();
    }

    public boolean equals(Object lazyPlugin) {
        if (lazyPlugin == this) {
            return true;
        }
        if (lazyPlugin != null && lazyPlugin instanceof LazyPlugin && getClass().isAssignableFrom(lazyPlugin.getClass())) {
            final LazyPlugin<?> other = (LazyPlugin<?>) lazyPlugin;
            if (!StringUtils.equals(getDisplayName(), other.getDisplayName())) {
                return false;
            }
            if (!StringUtils.equals(getClassName(), other.getClassName())) {
                return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return displayName.hashCode();
    }

    public final long getVersion() {
        return getLazyPluginClass().getRevision();
    }

    public abstract String getClassName();

    public final String getDisplayName() {
        return displayName;
    }

    public synchronized void setPluginClass(Class<T> pluginClass) {
        if (pluginClass == null) {
            this.pluginClass = null;
        } else {
            this.pluginClass = new WeakReference<Class<T>>(pluginClass);
        }
    }

    public boolean canHandle(String url) {
        if (patternBytes.length > 0) {
            final Matcher matcher = getMatcher();
            synchronized (matcher) {
                try {
                    if (matcher.reset(url).find()) {
                        final int matchLength = matcher.end() - matcher.start();
                        return matchLength > 0;
                    }
                } finally {
                    matcher.reset("");
                }
            }
        }
        return false;
    }

    public abstract T getPrototype(PluginClassLoaderChild classLoader, final boolean fallBackPlugin) throws UpdateRequiredClassNotFoundException;

    public synchronized T getPrototype(PluginClassLoaderChild classLoader) throws UpdateRequiredClassNotFoundException {
        if (classLoader != null && classLoader != getClassLoader(false)) {
            /* create new Instance because we have different classLoader given than ProtoTypeClassLoader */
            return newInstance(classLoader);
        }
        T ret = null;
        if (prototypeInstance != null && (ret = prototypeInstance.get()) != null) {
            return ret;
        }
        prototypeInstance = null;
        ret = newInstance(null);
        if (ret != null) {
            prototypeInstance = new WeakReference<T>(ret);
        }
        return ret;
    }

    public boolean isPrototype(T pluginInstance) {
        final WeakReference<T> prototypeInstance = this.prototypeInstance;
        return prototypeInstance != null && prototypeInstance.get() == pluginInstance;
    }

    public abstract T newInstance(PluginClassLoaderChild classLoader, final boolean fallBackPlugin) throws UpdateRequiredClassNotFoundException;

    public T newInstance(PluginClassLoaderChild classLoader) throws UpdateRequiredClassNotFoundException {
        T ret = null;
        try {
            final Class<T> clazz = getPluginClass(classLoader);
            final Constructor<T> cons = getConstructor(clazz);
            if (cons.getParameterTypes().length != 0) {
                ret = cons.newInstance(new Object[] { getPluginWrapper() });
            } else {
                ret = cons.newInstance(new Object[0]);
            }
        } catch (final Throwable e) {
            handleUpdateRequiredClassNotFoundException(e, true);
        }
        return ret;
    }

    private Constructor<T> getConstructor(Class<T> clazz) throws UpdateRequiredClassNotFoundException {
        try {
            return clazz.getConstructor(new Class[] { PluginWrapper.class });
        } catch (Throwable e) {
            handleUpdateRequiredClassNotFoundException(e, false);
            try {
                return clazz.getConstructor(new Class[] {});
            } catch (final Throwable e2) {
                handleUpdateRequiredClassNotFoundException(e, true);
            }
        }
        return null;
    }

    public void handleUpdateRequiredClassNotFoundException(Throwable e, boolean ThrowWTF) throws UpdateRequiredClassNotFoundException {
        if (e != null) {
            if (e instanceof NoClassDefFoundError) {
                NoClassDefFoundError ncdf = (NoClassDefFoundError) e;
                String classNotFound = ncdf.getMessage();
                ClassLoader lcl = Thread.currentThread().getContextClassLoader();
                if (lcl == null || !(lcl instanceof PluginClassLoaderChild)) {
                    lcl = getClassLoader(true);
                }
                if (lcl != null && lcl instanceof PluginClassLoaderChild) {
                    PluginClassLoaderChild pcl = (PluginClassLoaderChild) lcl;
                    if (pcl.isUpdateRequired(classNotFound)) {
                        throw new UpdateRequiredClassNotFoundException(classNotFound);
                    }
                }
            }
            if (e instanceof UpdateRequiredClassNotFoundException) {
                throw (UpdateRequiredClassNotFoundException) e;
            } else if (ThrowWTF) {
                throw new WTFException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized Class<T> getPluginClass(PluginClassLoaderChild classLoader) {
        if (classLoader != null && classLoader != getClassLoader(false)) {
            /* load class with custom classLoader because it's not default one */
            try {
                return (Class<T>) classLoader.loadClass(getClassName());
            } catch (Throwable e) {
                e.printStackTrace();
                throw new WTFException(e);
            }
        }
        Class<T> ret = null;
        if (pluginClass != null && (ret = pluginClass.get()) != null) {
            return ret;
        }
        pluginClass = null;
        try {
            ret = (Class<T>) getClassLoader(true).loadClass(getClassName());
        } catch (Throwable e) {
            e.printStackTrace();
            throw new WTFException(e);
        }
        if (ret != null) {
            pluginClass = new WeakReference<Class<T>>(ret);
        }
        return ret;
    }

    public final String getPatternSource() {
        if (patternBytes.length == 0) {
            return "";
        } else {
            return new String(patternBytes, UTF8);
        }
    }

    public final Matcher getMatcher() {
        Matcher ret = null;
        final MinTimeWeakReference<Matcher> lMatcher = matcher;
        if (lMatcher != null && (ret = lMatcher.get()) != null) {
            return ret;
        } else {
            matcher = new MinTimeWeakReference<Matcher>((ret = getPattern().matcher("")), 60 * 1000l, displayName, this);
            return ret;
        }
    }

    public final Pattern getCompiledPattern() {
        final MinTimeWeakReference<Pattern> lCompiledPattern = compiledPattern;
        if (lCompiledPattern != null) {
            return lCompiledPattern.get();
        } else {
            return null;
        }
    }

    public final Pattern getPattern() {
        Pattern ret = getCompiledPattern();
        if (ret == null) {
            if (Application.getJavaVersion() >= Application.JAVA17) {
                ret = Pattern.compile(getPatternSource(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
            } else {
                ret = Pattern.compile(getPatternSource(), Pattern.CASE_INSENSITIVE);
            }
            compiledPattern = new MinTimeWeakReference<Pattern>(ret, 60 * 1000l, displayName, this);
        }
        return ret;
    }

    @Override
    public synchronized void onMinTimeWeakReferenceCleanup(MinTimeWeakReference<?> minTimeWeakReference) {
        if (minTimeWeakReference == compiledPattern) {
            compiledPattern = null;
        } else if (minTimeWeakReference == classLoader) {
            classLoader = null;
        } else if (minTimeWeakReference == matcher) {
            matcher = null;
        }
    }

    /**
     * @return the classLoader
     */
    public synchronized PluginClassLoaderChild getClassLoader(boolean createNew) {
        PluginClassLoaderChild ret = null;
        if (classLoader != null && (ret = classLoader.get()) != null) {
            return ret;
        } else if (createNew == false) {
            return null;
        } else {
            ret = PluginClassLoader.getSharedChild(this);
            setClassLoader(ret);
            return ret;
        }
    }

    public synchronized void setClassLoader(PluginClassLoaderChild cl) {
        if (cl == null) {
            classLoader = null;
        } else {
            classLoader = new WeakReference<PluginClassLoader.PluginClassLoaderChild>(cl);
        }
    }

    @Override
    public String toString() {
        return getDisplayName() + "@" + getLazyPluginClass();
    }
}
