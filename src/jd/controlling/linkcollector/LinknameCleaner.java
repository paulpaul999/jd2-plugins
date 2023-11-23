package jd.controlling.linkcollector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.appwork.storage.config.ValidationException;
import org.appwork.storage.config.events.GenericConfigEventListener;
import org.appwork.storage.config.handler.KeyHandler;
import org.appwork.storage.config.handler.ObjectKeyHandler;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.os.CrossSystem;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter.ArchiveExtensions;
import org.jdownloader.controlling.filter.CompiledFiletypeFilter.ExtensionsFilterInterface;
import org.jdownloader.settings.staticreferences.CFG_GENERAL;

import jd.controlling.packagecontroller.AbstractNode;

public class LinknameCleaner {
    public static final Pattern   pat0     = Pattern.compile("(.*)(\\.|_|-)pa?r?t?\\.?[0-9]+.(rar|rev|exe)($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat1     = Pattern.compile("(.*)(\\.|_|-)part\\.?[0]*[1].(rar|rev|exe)($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat3     = Pattern.compile("(.*)\\.(?:rar|rev)($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat4     = Pattern.compile("(.*)\\.r\\d+($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat5     = Pattern.compile("(.*)(\\.|_|-)\\d+($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   par2     = Pattern.compile("(.*?)(\\.vol\\d+\\.par2$|\\.vol\\d+(?:\\+|-)\\d+\\.par2$|\\.par2$)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   par      = Pattern.compile("(.*?)(\\.p\\d+$|\\.par$)", Pattern.CASE_INSENSITIVE);
    public static final Pattern[] rarPats  = new Pattern[] { pat0, pat1, pat3, pat4, pat5, par2, par };
    public static final Pattern   pat6     = Pattern.compile("(.*)\\.zip($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat7     = Pattern.compile("(.*)\\.z\\d+($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat8     = Pattern.compile("(?is).*\\.7z\\.[\\d]+($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat9     = Pattern.compile("(.*)\\.a.($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern[] zipPats  = new Pattern[] { pat6, pat7, pat8, pat9 };
    public static final Pattern   pat10    = Pattern.compile("(.*)\\._((_[a-z]{1})|([a-z]{2}))(\\.|$)");
    public static Pattern         pat11    = null;
    public static Pattern[]       ffsjPats = null;
    static {
        try {
            /* this should be done on a better way with next major update */
            pat11 = Pattern.compile("(.+)(" + jd.plugins.hoster.DirectHTTP.ENDINGS + "$)", Pattern.CASE_INSENSITIVE);
            ffsjPats = new Pattern[] { pat10, pat11 };
        } catch (final Throwable e) {
            /* not loaded yet */
        }
    }
    public static final Pattern   pat12   = Pattern.compile("(CD\\d+)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat13   = Pattern.compile("(part\\d+)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat17   = Pattern.compile("(.+)\\.\\d+\\.xtm($|\\.html?)");
    public static final Pattern   pat18   = Pattern.compile("(.*)\\.isz($|\\.html?)", Pattern.CASE_INSENSITIVE);
    public static final Pattern   pat19   = Pattern.compile("(.*)\\.i\\d{2}$", Pattern.CASE_INSENSITIVE);
    public static final Pattern[] iszPats = new Pattern[] { pat18, pat19 };

    public static enum EXTENSION_SETTINGS {
        KEEP,
        REMOVE_KNOWN,
        REMOVE_ALL
    }

    private static volatile Map<Pattern, String> FILENAME_REPLACEMAP         = new HashMap<Pattern, String>();
    private static volatile Map<Pattern, String> FILENAME_REPLACEMAP_DEFAULT = new HashMap<Pattern, String>();
    static {
        final ObjectKeyHandler replaceMapKeyHandler = CFG_GENERAL.FILENAME_CHARACTER_REGEX_REPLACEMAP;
        replaceMapKeyHandler.getEventSender().addListener(new GenericConfigEventListener<Object>() {
            @Override
            public void onConfigValueModified(KeyHandler<Object> keyHandler, Object newValue) {
                FILENAME_REPLACEMAP = convertReplaceMap((Map<String, String>) newValue);
            }

            @Override
            public void onConfigValidatorError(KeyHandler<Object> keyHandler, Object invalidValue, ValidationException validateException) {
            }
        });
        FILENAME_REPLACEMAP = convertReplaceMap((Map<String, String>) replaceMapKeyHandler.getValue());
        FILENAME_REPLACEMAP_DEFAULT = convertReplaceMap((Map<String, String>) replaceMapKeyHandler.getDefaultValue());
    }
    private static volatile Map<Pattern, String> PACKAGENAME_REPLACEMAP         = new HashMap<Pattern, String>();
    private static volatile Map<Pattern, String> PACKAGENAME_REPLACEMAP_DEFAULT = new HashMap<Pattern, String>();
    static {
        final ObjectKeyHandler replaceMapKeyHandler = CFG_GENERAL.PACKAGE_NAME_CHARACTER_REGEX_REPLACEMAP;
        replaceMapKeyHandler.getEventSender().addListener(new GenericConfigEventListener<Object>() {
            @Override
            public void onConfigValueModified(KeyHandler<Object> keyHandler, Object newValue) {
                PACKAGENAME_REPLACEMAP = convertReplaceMap((Map<String, String>) newValue);
            }

            @Override
            public void onConfigValidatorError(KeyHandler<Object> keyHandler, Object invalidValue, ValidationException validateException) {
            }
        });
        PACKAGENAME_REPLACEMAP = convertReplaceMap((Map<String, String>) replaceMapKeyHandler.getValue());
        PACKAGENAME_REPLACEMAP_DEFAULT = convertReplaceMap((Map<String, String>) replaceMapKeyHandler.getDefaultValue());
    }

    private static Map<Pattern, String> convertReplaceMap(final Map<String, String> replaceMap) {
        final Map<Pattern, String> ret = new HashMap<Pattern, String>();
        if (replaceMap != null) {
            for (Entry<String, String> entry : replaceMap.entrySet()) {
                if (StringUtils.isNotEmpty(entry.getKey()) && entry.getValue() != null) {
                    try {
                        ret.put(Pattern.compile(entry.getKey()), entry.getValue());
                    } catch (final PatternSyntaxException e) {
                    }
                }
            }
        }
        if (ret.size() > 0) {
            return ret;
        } else {
            return null;
        }
    }

    private static String replaceCharactersByMap(final String str, final Map<Pattern, String> forbiddenCharacterRegexReplaceMap) {
        if (forbiddenCharacterRegexReplaceMap == null) {
            return str;
        }
        String newstr = str;
        final Iterator<Entry<Pattern, String>> iterator = forbiddenCharacterRegexReplaceMap.entrySet().iterator();
        while (iterator.hasNext()) {
            final Entry<Pattern, String> entry = iterator.next();
            final Pattern pattern = entry.getKey();
            final String replacement = entry.getValue();
            if (replacement != null) {
                try {
                    newstr = pattern.matcher(newstr).replaceAll(replacement);
                } catch (final PatternSyntaxException e) {
                }
            }
        }
        /**
         * Users can put anything into that replace map. </br>
         * Try to avoid the results of adding something like ".+" resulting in empty filenames.
         */
        if (!StringUtils.isEmpty(newstr)) {
            return newstr;
        } else {
            /* Fallback */
            return str;
        }
    }

    public static String cleanFilename(final String filename, final boolean removeLeadingHidingDot) {
        String newfinalFileName = filename;
        newfinalFileName = replaceCharactersByMap(filename, FILENAME_REPLACEMAP);
        /* We can never know how users alter the setting so let's do one more round with our default replace map. */
        newfinalFileName = replaceCharactersByMap(filename, FILENAME_REPLACEMAP_DEFAULT);
        newfinalFileName = CrossSystem.alleviatePathParts(newfinalFileName, removeLeadingHidingDot);
        return newfinalFileName;
    }

    public static String cleanPackagename(String name, boolean splitUpperLowerCase, boolean ignoreArchiveFilters, final EXTENSION_SETTINGS extensionSettings, boolean allowCleanup) {
        return cleanPackagename(null, name, splitUpperLowerCase, ignoreArchiveFilters, extensionSettings, allowCleanup);
    }

    public static String cleanPackagename(AbstractNode node, String name, boolean splitUpperLowerCase, boolean ignoreArchiveFilters, final EXTENSION_SETTINGS extensionSettings, boolean allowCleanup) {
        if (name == null) {
            return null;
        }
        /*
         * Basic cleanup: Remove typical invalid characters because in many cases the package name will be used as part of our download
         * path.
         */
        name = replaceCharactersByMap(name, PACKAGENAME_REPLACEMAP);
        name = replaceCharactersByMap(name, PACKAGENAME_REPLACEMAP_DEFAULT);
        boolean extensionStilExists = true;
        String before = name;
        if (!ignoreArchiveFilters) {
            /** remove rar extensions */
            for (Pattern Pat : rarPats) {
                name = getNameMatch(name, Pat);
                if (!before.equals(name)) {
                    extensionStilExists = false;
                    break;
                }
            }
            if (extensionStilExists) {
                /**
                 * remove 7zip/zip and merge extensions
                 */
                before = name;
                for (Pattern Pat : zipPats) {
                    name = getNameMatch(name, Pat);
                    if (!before.equals(name)) {
                        extensionStilExists = false;
                        break;
                    }
                }
            }
            if (extensionStilExists) {
                /**
                 * remove isz extensions
                 */
                before = name;
                for (Pattern Pat : iszPats) {
                    name = getNameMatch(name, Pat);
                    if (!before.equals(name)) {
                        extensionStilExists = false;
                        break;
                    }
                }
            }
            if (extensionStilExists) {
                before = name;
                /* xtremsplit */
                name = getNameMatch(name, pat17);
                if (!before.equals(name)) {
                    extensionStilExists = false;
                }
            }
            if (extensionStilExists && ffsjPats != null) {
                /**
                 * FFSJ splitted files
                 *
                 */
                before = name;
                for (Pattern Pat : ffsjPats) {
                    name = getNameMatch(name, Pat);
                    if (!before.equalsIgnoreCase(name)) {
                        extensionStilExists = false;
                        break;
                    }
                }
            }
        }
        /**
         * remove CDx,Partx
         */
        String tmpname = cutNameMatch(name, pat12);
        if (tmpname.length() > 3) {
            name = tmpname;
        }
        tmpname = cutNameMatch(name, pat13);
        if (tmpname.length() > 3) {
            name = tmpname;
        }
        /* remove extension */
        if (EXTENSION_SETTINGS.REMOVE_ALL.equals(extensionSettings) || EXTENSION_SETTINGS.REMOVE_KNOWN.equals(extensionSettings)) {
            while (true) {
                final int lastPoint = name.lastIndexOf(".");
                if (lastPoint > 0) {
                    final int extLength = (name.length() - (lastPoint + 1));
                    final String ext = name.substring(lastPoint + 1);
                    final ExtensionsFilterInterface knownExt = CompiledFiletypeFilter.getExtensionsFilterInterface(ext);
                    if (knownExt != null && !ArchiveExtensions.NUM.equals(knownExt)) {
                        /* make sure to cut off only known extensions */
                        name = name.substring(0, lastPoint);
                    } else if (extLength <= 4 && EXTENSION_SETTINGS.REMOVE_ALL.equals(extensionSettings) && ext.matches("^[0-9a-zA-z]+$")) {
                        /* make sure to cut off only known extensions */
                        if (extensionStilExists) {
                            name = name.substring(0, lastPoint);
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        /* remove ending ., - , _ */
        int removeIndex = -1;
        for (int i = name.length() - 1; i >= 0; i--) {
            final char c = name.charAt(i);
            if (c == ',' || c == '_' || c == '-') {
                removeIndex = i;
            } else {
                break;
            }
        }
        if (removeIndex > 0) {
            name = name.substring(0, removeIndex);
        }
        /* if enabled, replace dots and _ with spaces and do further clean ups */
        if (allowCleanup && org.jdownloader.settings.staticreferences.CFG_GENERAL.CLEAN_UP_PACKAGENAMES.isEnabled()) {
            StringBuilder sb = new StringBuilder();
            char[] cs = name.toCharArray();
            char lastChar = 'a';
            for (int i = 0; i < cs.length; i++) {
                // splitFileNamesLikeThis to "split File Names Like This"
                if (splitUpperLowerCase && i > 0 && Character.isUpperCase(cs[i]) && Character.isLowerCase(cs[i - 1])) {
                    if (lastChar != ' ') {
                        sb.append(' ');
                    }
                    lastChar = ' ';
                }
                switch (cs[i]) {
                case '_':
                case '.':
                    if (lastChar != ' ') {
                        sb.append(' ');
                    }
                    lastChar = ' ';
                    break;
                default:
                    lastChar = cs[i];
                    sb.append(cs[i]);
                }
            }
            name = sb.toString();
        }
        return name.trim();
    }

    private static String getNameMatch(String name, Pattern pattern) {
        String match = new Regex(name, pattern).getMatch(0);
        if (match != null) {
            return match;
        }
        return name;
    }

    public static int comparepackages(String a, String b) {
        int c = 0;
        String aa = a.toLowerCase();
        String bb = b.toLowerCase();
        for (int i = 0; i < Math.min(aa.length(), bb.length()); i++) {
            if (aa.charAt(i) == bb.charAt(i)) {
                c++;
            }
        }
        if (Math.min(aa.length(), bb.length()) == 0) {
            return 0;
        }
        return c * 100 / Math.max(aa.length(), bb.length());
    }

    private static String cutNameMatch(String name, Pattern pattern) {
        if (name == null) {
            return null;
        }
        String match = new Regex(name, pattern).getMatch(0);
        if (match != null) {
            int firstpos = name.indexOf(match);
            String tmp = name.substring(0, firstpos);
            int lastpos = name.indexOf(match) + match.length();
            if (!(lastpos > name.length())) {
                tmp = tmp + name.substring(lastpos);
            }
            name = tmp;
            /* remove seq. dots */
            name = name.replaceAll("\\.{2,}", ".");
            name = name.replaceAll("\\.{2,}", ".");
        }
        return name;
    }
}
