package org.jdownloader.extensions.extraction.multi;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import net.sf.sevenzipjbinding.ArchiveFormat;

import org.appwork.utils.Hash;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.HexFormatter;
import org.jdownloader.extensions.extraction.Archive;
import org.jdownloader.extensions.extraction.ArchiveFactory;
import org.jdownloader.extensions.extraction.ArchiveFile;
import org.jdownloader.extensions.extraction.FileSignatures;
import org.jdownloader.extensions.extraction.MissingArchiveFile;
import org.jdownloader.logging.LogController;

public enum ArchiveType {
    /**
     * DO NOT CHANGE ORDER: some archiveTypes share same extension!
     */
    /**
     * Multipart RAR Archive (.part01.rar, .part02.rar...), 0-999 -> max 1000 parts
     */
    RAR_MULTI {
        /**
         * naming, see http://www.win-rar.com/press/downloads/Split_Files.pdf and http://kb.winzip.com/kb/entry/154/
         */
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.(part|p)(\\.?)(\\d{1,3})(\\..*?|)\\.rar$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return getRARArchiveFormat(archive);
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, final Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\." + matches[1] + escapeRegex(matches[2]) + "\\d{" + matches[3].length() + "}" + escapeRegex(matches[4]) + "\\.(?i)rar";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, final Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, isMultiPart) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            final String matches[] = getMatches(filePathOrName);
            return matches != null ? matches[3] : null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return Integer.parseInt(partNumberString);
        }

        @Override
        protected int getFirstPartIndex() {
            return 1;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 2;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + "." + matches[1] + matches[2] + String.format(Locale.US, "%0" + partStringLength + "d", partIndex) + matches[4] + ".rar";
        }

        @Override
        protected Boolean isMultiPart(ArchiveFile archiveFile, boolean verifiedResult) {
            return RAR_SINGLE.isMultiPart(archiveFile, false);
        }

        @Override
        public String getIconExtension() {
            return RAR_SINGLE.getIconExtension();
        }

        @Override
        protected boolean isMultiPartType() {
            return true;
        }
    },
    /**
     * Multipart RAR Archive (.000.rar, .001.rar...) 000-999 -> max 1000 parts
     */
    RAR_MULTI2 {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.(\\d{3})\\.rar$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return getRARArchiveFormat(archive);
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, final Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.\\d{3}\\.(?i)rar";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, final Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, isMultiPart) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            final String matches[] = getMatches(filePathOrName);
            return matches != null ? matches[1] : null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return Integer.parseInt(partNumberString);
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 1;
        }

        @Override
        protected boolean isMultiPartType() {
            return true;
        }

        private final int multiPartThreshold = 50;

        @Override
        protected boolean looksLikeAnArchive(BitSet bitset, ArchiveFile archiveFiles[]) {
            int setCount = 0;
            for (int index = 0; index < bitset.length(); index++) {
                if (bitset.get(index)) {
                    setCount++;
                }
            }
            /**
             * for this type we need at least multiPartThreshold available parts
             */
            return (setCount * 100) / bitset.length() > multiPartThreshold;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + "." + String.format(Locale.US, "%0" + partStringLength + "d", partIndex) + ".rar";
        }

        @Override
        protected Boolean isMultiPart(ArchiveFile archiveFile, boolean verifiedResult) {
            return RAR_SINGLE.isMultiPart(archiveFile, false);
        }

        @Override
        public String getIconExtension() {
            return RAR_SINGLE.getIconExtension();
        }
    },
    /**
     * Multipart RAR Archive (.rar, .r00, .r01...,.s00....), 1(rar) + 9(r,s,t...z)*100(00-99) Parts = 901 parts
     */
    RAR_MULTI3 {
        private final Pattern patternPart  = Pattern.compile("(?i)(.*)\\.([r-z]\\d{2})$");
        private final Pattern patternStart = Pattern.compile("(?i)(.*)\\.rar$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return getRARArchiveFormat(archive);
        }

        @Override
        protected boolean isMultiPartType() {
            return true;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && patternPart.matcher(filePathOrName).matches() || patternStart.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)([r-z]\\d{2}|rar)";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, isMultiPart) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            if (filePathOrName != null) {
                String matches[] = new Regex(filePathOrName, patternPart).getRow(0);
                if (matches == null) {
                    matches = new Regex(filePathOrName, patternStart).getRow(0);
                }
                return matches;
            }
            return null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            final String matches[] = new Regex(filePathOrName, patternPart).getRow(0);
            return matches != null ? matches[1] : null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            if (partNumberString == null) {
                return 0;
            } else {
                final String number = partNumberString.substring(1);
                final int base = partNumberString.charAt(0) - 'r';
                return (base * 100) + Integer.parseInt(number) + 1;
            }
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected boolean looksLikeAnArchive(BitSet bitset, ArchiveFile archiveFiles[]) {
            int setCount = 0;
            for (int index = 0; index < bitset.length(); index++) {
                if (bitset.get(index)) {
                    setCount++;
                }
            }
            /**
             * for this type we need at least 2 parts or a nonstart part
             */
            return setCount > 1 || bitset.length() > 1;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            if (partIndex == 0) {
                return matches[0] + ".rar";
            } else {
                int start = 'r';
                int index = partIndex - 1;
                while (true) {
                    if (index < 100) {
                        return matches[0] + "." + String.valueOf((char) start) + String.format(Locale.US, "%02d", (index));
                    }
                    index -= 100;
                    start = start + 1;
                }
            }
        }

        @Override
        protected Boolean isMultiPart(ArchiveFile archiveFile, boolean verifiedResult) {
            return RAR_SINGLE.isMultiPart(archiveFile, false);
        }

        @Override
        public String getIconExtension() {
            return RAR_SINGLE.getIconExtension();
        }
    },
    /**
     * SinglePart RAR Archive (.rar) (.rar)
     */
    RAR_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.rar$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return getRARArchiveFormat(archive);
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)rar";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, isMultiPart) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".rar";
        }

        /**
         * https://forensics.wiki/rar/
         *
         * http://www.rarlab.com/technote.htm#rarsign
         *
         * DOES ONLY CHECK VALID RAR FILE, NOT IF ITS A VOLUME FILE if partIndex >0
         */
        @Override
        protected Boolean isValidPart(int partIndex, ArchiveFile archiveFile, boolean verifiedResult) {
            if (archiveFile == null) {
                return false;
            } else if (partIndex == -1) {
                return null;
            } else if (archiveFile.exists(verifiedResult)) {
                final String signatureString;
                try {
                    signatureString = FileSignatures.readFileSignature(new File(archiveFile.getFilePath()), 4);
                } catch (IOException e) {
                    LogController.CL().log(e);
                    return false;
                }
                if (signatureString.length() >= 8) {
                    return signatureString.startsWith("52617221");
                }
            }
            return verifiedResult ? false : null;
        }

        /**
         * https://forensics.wiki/rar/
         *
         * http://www.rarlab.com/technote.htm#rarsign
         *
         * bytes are little endian format!
         *
         */
        @Override
        protected Boolean isMultiPart(ArchiveFile archiveFile, boolean verifiedResult) {
            if (archiveFile.exists(verifiedResult)) {
                final String signatureString;
                try {
                    signatureString = FileSignatures.readFileSignature(new File(archiveFile.getFilePath()), 32);
                } catch (IOException e) {
                    LogController.CL().log(e);
                    return false;
                }
                if (signatureString.length() >= 8) {
                    final boolean isRAR4x = StringUtils.startsWithCaseInsensitive(signatureString, "526172211A0700");
                    final boolean isRAR5x = StringUtils.startsWithCaseInsensitive(signatureString, "526172211A070100");
                    if (isRAR4x && signatureString.length() >= 12 * 2) {
                        // RAR 4.x 0x52 0x61 0x72 0x21 0x1A 0x07 0x00 (MARK_HEAD)
                        /*
                         * 0x0001 Volume/Archive, bit 0
                         * 
                         * 0x0002 Comment
                         * 
                         * 0x0004 Lock Archive
                         * 
                         * 0x0008 Solid Archive
                         * 
                         * 0x0010 New Volume naming scheme (.partN.rar), bit 4
                         * 
                         * 0x0100 First Volume (only in RAR 3.0 and later), bit 8
                         */
                        final boolean archiveHeader = "73".equals(signatureString.substring(18, 20));
                        final String flagsBits = signatureString.substring(22, 24) + signatureString.substring(20, 22);
                        final int flags = Integer.parseInt(flagsBits, 16);
                        final boolean isVolume = (flags & (1 << 0)) != 0;
                        final boolean isComment = (flags & (1 << 1)) != 0;
                        final boolean isLock = (flags & (1 << 2)) != 0;
                        final boolean isSolid = (flags & (1 << 3)) != 0;
                        final boolean isNewVolumeNamingScheme = (flags & (1 << 4)) != 0;
                        final boolean isFirstVolume = (flags & (1 << 8)) != 0;
                        final boolean isMultiPart = archiveHeader && isVolume;
                        return isMultiPart;
                    } else if (isRAR5x && signatureString.length() >= 17 * 2) {
                        // RAR 5.x 0x52 0x61 0x72 0x21 0x1A 0x07 0x01 0x00 (MARK_HEAD)
                        /*
                         *
                         * 0x0001   Volume. Archive is a part of multivolume set.
                         *
                         * 0x0002   Volume number field is present. This flag is present in all volumes except first.
                         *
                         * 0x0004   Solid archive.
                         *
                         * 0x0008   Recovery record is present.
                         *
                         * 0x0010   Locked archive.
                         */
                        try {
                            final ByteArrayInputStream is = new ByteArrayInputStream(HexFormatter.hexToByteArray(signatureString.substring(24)));
                            final long headerSize = readVarInt(is, true);
                            final long headerType = readVarInt(is, true);
                            if (headerType == 1) {
                                // Main archive header
                                final long headerFlags = readVarInt(is, true);
                                final long extraAreaSize = readVarInt(is, true);
                                final long archiveFlags = readVarInt(is, true);
                                final boolean isVolume = (archiveFlags & (1 << 0)) != 0;
                                final boolean isVolumeNumberPresent = (archiveFlags & (1 << 1)) != 0;
                                final boolean isSolidArchive = (archiveFlags & (1 << 2)) != 0;
                                final boolean isRecoveryRecordPreset = (archiveFlags & (1 << 3)) != 0;
                                final boolean isLockedArchive = (archiveFlags & (1 << 4)) != 0;
                                final boolean isMultiPart = isVolume;
                                return isMultiPart;
                            } else if (headerType == 4) {
                                // Archive encryption header
                                // cannot check Main archive header because of encryption
                                return null;
                            }
                        } catch (IOException ignore) {
                        }
                    } else {
                        // unknown RAR version?
                        return null;
                    }
                    return verifiedResult ? false : null;
                }
            }
            return verifiedResult ? false : null;
        }

        private long readVarInt(InputStream is, final boolean alwaysThrowEOF) throws IOException {
            long ret = 0;
            int shift = 0;
            while (true) {
                final int read = is.read();
                if (read == -1) {
                    if (shift > 0 && alwaysThrowEOF) {
                        throw new EOFException();
                    } else {
                        return -1;
                    }
                } else {
                    final long value = read & 0x7f;
                    final int msb = (read & 0xff) >> 7;
                    ret |= (value << shift);
                    if (msb == 0) {
                        return ret;
                    } else {
                        shift += 7;
                    }
                }
            }
        }

        @Override
        public String getIconExtension() {
            return "rar";
        }
    },
    /**
     * Multipart 7Zip Archive (.7z.001, 7z.002...) 0-9999 -> max 1000 parts
     */
    SEVENZIP_PARTS {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.7z\\.(\\d{1,3})$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.SEVEN_ZIP;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)7z\\.\\d{" + matches[1].length() + "}";
            }
        }

        @Override
        protected boolean isMultiPartType() {
            return true;
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            final String matches[] = getMatches(filePathOrName);
            return matches != null ? matches[1] : null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return Integer.parseInt(partNumberString);
        }

        @Override
        protected int getFirstPartIndex() {
            return 1;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 1;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".7z." + String.format(Locale.US, "%0" + partStringLength + "d", partIndex);
        }

        @Override
        public String getIconExtension() {
            return SEVENZIP_SINGLE.getIconExtension();
        }
    },
    /**
     * Multipart Zip Archive (.zip, .z01...) 0-999 -> max 1000 parts
     */
    ZIP_MULTI2 {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.z(ip|\\d{1,3})$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.ZIP;
        }

        @Override
        protected boolean isMultiPartType() {
            return true;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)z(ip|\\d{" + matches[1].length() + "})";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            final String matches[] = getMatches(filePathOrName);
            return matches != null ? matches[1] : null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            if ("ip".equals(partNumberString.toLowerCase(Locale.ENGLISH))) {
                return 0;
            } else {
                return Integer.parseInt(partNumberString);
            }
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected boolean looksLikeAnArchive(BitSet bitset, ArchiveFile archiveFiles[]) {
            for (int index = 0; index < bitset.length(); index++) {
                if (bitset.get(index) && index > 0) {
                    // at least one zxx part to make sure it is a multipart archive
                    return true;
                }
            }
            return false;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            if (partIndex == 0) {
                return matches[0] + ".zip";
            } else {
                return matches[0] + ".z" + String.format(Locale.US, "%0" + partStringLength + "d", partIndex);
            }
        }

        @Override
        public String getIconExtension() {
            return ZIP_SINGLE.getIconExtension();
        }
    },
    /**
     * Multipart Zip Archive (.zip.001, .zip.002...) 0-999 -> max 1000 parts
     */
    ZIP_MULTI {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.zip\\.(\\d{1,3})$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.ZIP;
        }

        @Override
        protected boolean isMultiPartType() {
            return true;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)zip\\.\\d{" + matches[1].length() + "}";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            final String matches[] = getMatches(filePathOrName);
            return matches != null ? matches[1] : null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return Integer.parseInt(partNumberString);
        }

        @Override
        protected int getFirstPartIndex() {
            return 1;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 2;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".zip." + String.format(Locale.US, "%0" + partStringLength + "d", partIndex);
        }

        @Override
        public String getIconExtension() {
            return ZIP_SINGLE.getIconExtension();
        }
    },
    /**
     * SinglePart 7zip Archive (.7z)
     */
    SEVENZIP_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.7z$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.SEVEN_ZIP;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)7z";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".7z";
        }

        @Override
        public String getIconExtension() {
            return "7z";
        }
    },
    /**
     * SinglePart Zip Archive (.7z)
     */
    ZIP_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.zip$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.ZIP;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)zip";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".zip";
        }

        @Override
        public String getIconExtension() {
            return "zip";
        }
    },
    /**
     * SinglePart LZH Archive (.lzh or .lha)
     */
    LZH_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.(lha|lzh)$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.LZH;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\." + matches[1];
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + "." + matches[1];
        }

        @Override
        public String getIconExtension() {
            return "lzh";
        }
    },
    /**
     * SinglePart LZH Archive (.tar)
     */
    TAR_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.tar$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.TAR;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)tar";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".tar";
        }

        @Override
        public String getIconExtension() {
            return "tar";
        }
    },
    /**
     * SinglePart ARJ Archive (.arj)
     */
    ARJ_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.arj$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.ARJ;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)arj";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".arj";
        }

        @Override
        public String getIconExtension() {
            return "arj";
        }
    },
    /**
     * SinglePart CPIO Archive (.cpio)
     */
    CPIO_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.cpio$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.CPIO;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)cpio";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".cpio";
        }

        @Override
        public String getIconExtension() {
            return "cpio";
        }
    },
    /**
     * SinglePart Tar.GZ Archive (.tgz)
     */
    TGZ_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.tgz$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.GZIP;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)tgz";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".tgz";
        }

        @Override
        public String getIconExtension() {
            return "tgz";
        }
    },
    /**
     * SinglePart GZIP Archive (.gz)
     */
    GZIP_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.gz$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.GZIP;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)gz";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".gz";
        }

        @Override
        public String getIconExtension() {
            return "gz";
        }
    },
    /**
     * SinglePart BZIP2 Archive (.bz2)
     */
    BZIP2_SINGLE {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.bz2$");

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return ArchiveFormat.BZIP2;
        }

        @Override
        protected boolean isMultiPartType() {
            return false;
        }

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.(?i)bz2";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.TRUE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        protected String getPartNumberString(String filePathOrName) {
            return null;
        }

        @Override
        protected int getPartNumber(String partNumberString) {
            return 0;
        }

        @Override
        protected int getFirstPartIndex() {
            return 0;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 0;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + ".bz2";
        }

        @Override
        public String getIconExtension() {
            return "bz2";
        }
    },
    /**
     * Multipart RAR Archive Archive (.001, .002 ...) MUST BE LAST ONE!! DO NOT CHANGE ORDER 000-999 -> max 1000 parts
     */
    RAR_MULTI4 {
        private final Pattern pattern = Pattern.compile("(?i)(.*)\\.([0-9]{3})$");

        @Override
        public boolean matches(String filePathOrName) {
            return filePathOrName != null && pattern.matcher(filePathOrName).matches();
        }

        @Override
        protected boolean isMultiPartType() {
            return true;
        }

        @Override
        protected String buildIDPattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                return "\\.[0-9]{3}";
            }
        }

        @Override
        public Pattern buildArchivePattern(String[] matches, Boolean isMultiPart) {
            if (Boolean.FALSE.equals(isMultiPart)) {
                return null;
            } else {
                final String pattern = "^" + escapeRegex(matches[0]) + buildIDPattern(matches, null) + "$";
                return Pattern.compile(pattern);
            }
        }

        @Override
        public String[] getMatches(String filePathOrName) {
            return filePathOrName != null ? new Regex(filePathOrName, pattern).getRow(0) : null;
        }

        @Override
        public String getPartNumberString(String filePathOrName) {
            final String matches[] = getMatches(filePathOrName);
            return matches != null ? matches[1] : null;
        }

        @Override
        public int getPartNumber(String partNumberString) {
            return Integer.parseInt(partNumberString);
        }

        @Override
        protected int getFirstPartIndex() {
            return 1;
        }

        @Override
        protected int getMinimumNeededPartIndex() {
            return 2;
        }

        @Override
        protected String buildMissingPart(String[] matches, int partIndex, int partStringLength) {
            return matches[0] + "." + String.format(Locale.US, "%0" + partStringLength + "d", partIndex);
        }

        @Override
        protected boolean looksLikeAnArchive(BitSet bitset, ArchiveFile archiveFiles[]) {
            int count = 0;
            for (int index = 0; index < bitset.length(); index++) {
                if (bitset.get(index)) {
                    if (++count == 2) {
                        return true;
                    }
                } else {
                    count = 0;
                }
            }
            return false;
        }

        @Override
        public ArchiveFormat getArchiveFormat(Archive archive) throws IOException {
            return getRARArchiveFormat(archive);
        }

        @Override
        protected Boolean isValidPart(int partIndex, ArchiveFile archiveFile, boolean verifiedResult) {
            if (archiveFile == null) {
                return false;
            } else if (partIndex == -1) {
                return null;
            } else {
                return RAR_SINGLE.isValidPart(partIndex, archiveFile, false);
            }
        }

        @Override
        public String getIconExtension() {
            return RAR_SINGLE.getIconExtension();
        }
    };
    protected String escapeRegex(String input) {
        if (input.length() == 0) {
            return "";
        } else {
            return Regex.escape(input);
        }
    }

    /**
     * http://www.rarlab.com/technote.htm
     */
    private static ArchiveFormat getRARArchiveFormat(final Archive archive) throws IOException {
        if (Multi.isRAR5Supported() && archive != null && archive.getArchiveFiles() != null && archive.getArchiveFiles().size() > 0) {
            final ArchiveFile firstArchiveFile = archive.getArchiveFiles().get(0);
            final String signatureString = FileSignatures.readFileSignature(new File(firstArchiveFile.getFilePath()), 14);
            if (signatureString.length() >= 16 && StringUtils.startsWithCaseInsensitive(signatureString, "526172211a070100")) {
                return ArchiveFormat.valueOf("RAR5");
            }
        }
        return ArchiveFormat.RAR;
    }

    public abstract ArchiveFormat getArchiveFormat(Archive archive) throws IOException;

    public abstract boolean matches(final String filePathOrName);

    public abstract String[] getMatches(final String filePathOrName);

    public abstract Pattern buildArchivePattern(String[] matches, Boolean isMultiPart);

    protected abstract String buildIDPattern(String[] matches, Boolean isMultiPart);

    protected abstract String getPartNumberString(final String filePathOrName);

    protected abstract int getPartNumber(final String partNumberString);

    protected abstract int getFirstPartIndex();

    protected abstract int getMinimumNeededPartIndex();

    protected abstract boolean isMultiPartType();

    protected abstract String buildMissingPart(String[] matches, int partIndex, int partStringLength);

    protected boolean looksLikeAnArchive(BitSet bitset, ArchiveFile archiveFiles[]) {
        return bitset.size() != 0;
    }

    protected Boolean isValidPart(int partIndex, ArchiveFile archiveFile, boolean verifiedResult) {
        return archiveFile != null;
    }

    protected Boolean isMultiPart(final ArchiveFile archiveFile, boolean verifiedResult) {
        return null;
    }

    public abstract String getIconExtension();

    public static ArchiveFile getLastArchiveFile(final Archive archive) {
        final ArchiveType type = archive.getArchiveType();
        if (type != null) {
            int index = -1;
            ArchiveFile ret = null;
            for (final ArchiveFile archiveFile : archive.getArchiveFiles()) {
                final int partNum = type.getPartNumber(type.getPartNumberString(archiveFile.getFilePath()));
                if (index == -1 || partNum > index) {
                    index = partNum;
                    ret = archiveFile;
                }
            }
            return ret;
        } else {
            return null;
        }
    }

    public ArchiveFile getBestArchiveFileMatch(final Archive archive, final String fileName) {
        final ArchiveType archiveType = archive.getArchiveType();
        if (archiveType == this) {
            final String partNumberString = archiveType.getPartNumberString(fileName);
            final int partNumber = archiveType.getPartNumber(partNumberString);
            for (final ArchiveFile archiveFile : archive.getArchiveFiles()) {
                if (partNumber == archiveType.getPartNumber(archiveType.getPartNumberString(archiveFile.getName()))) {
                    return archiveFile;
                }
            }
        }
        return null;
    }

    public static List<ArchiveFile> getMissingArchiveFiles(Archive archive, ArchiveType archiveType, int numberOfParts) {
        final ArchiveFile firstArchiveFile = archive.getArchiveFiles().size() > 0 ? archive.getArchiveFiles().get(0) : null;
        if (firstArchiveFile != null) {
            final String linkPath = firstArchiveFile.getFilePath();
            final String[] filePathParts = archiveType.getMatches(linkPath);
            if (filePathParts != null) {
                final BitSet availableParts = new BitSet();
                int partStringLength = 1;
                for (final ArchiveFile archiveFile : archive.getArchiveFiles()) {
                    final String fileName = archiveFile.getName();
                    final String partNumberString = archiveType.getPartNumberString(fileName);
                    final int partNumber = archiveType.getPartNumber(partNumberString);
                    if (partNumberString != null) {
                        partStringLength = Math.max(partStringLength, partNumberString.length());
                    }
                    if (partNumber >= 0) {
                        availableParts.set(partNumber);
                    }
                }
                final List<ArchiveFile> ret = new ArrayList<ArchiveFile>();
                /**
                 * some archives start at 0 (0...x-1)
                 *
                 * some archives start at 1 (1....x)
                 */
                final int minimumParts = Math.max(archiveType.getMinimumNeededPartIndex(), numberOfParts) - (1 - archiveType.getFirstPartIndex());
                for (int partIndex = archiveType.getFirstPartIndex(); partIndex <= minimumParts; partIndex++) {
                    if (availableParts.get(partIndex) == false) {
                        final File missingFile = new File(archiveType.buildMissingPart(filePathParts, partIndex, partStringLength));
                        ret.add(new MissingArchiveFile(missingFile.getName(), missingFile.getAbsolutePath()));
                    }
                }
                return ret;
            }
        }
        return null;
    }

    public static Archive createArchive(final ArchiveFactory link, final boolean allowDeepInspection, final ArchiveType... archiveTypes) throws ArchiveException {
        final String linkPath = link.getFilePath();
        Boolean isKnownMultiPart = null;
        archiveTypeLoop: for (final ArchiveType archiveType : archiveTypes) {
            if (isKnownMultiPart != null && archiveType.isMultiPartType() != isKnownMultiPart.booleanValue()) {
                continue archiveTypeLoop;
            }
            final String[] filePathParts = archiveType.getMatches(linkPath);
            final Boolean validPart = filePathParts != null ? archiveType.isValidPart(-1, link, false) : null;
            if (filePathParts != null && !Boolean.FALSE.equals(validPart)) {
                final Boolean isMultiPart;
                if (isKnownMultiPart != null) {
                    isMultiPart = isKnownMultiPart;
                } else if (allowDeepInspection) {
                    isMultiPart = archiveType.isMultiPart(link, false);
                } else {
                    isMultiPart = null;
                }
                if (isKnownMultiPart == null) {
                    isKnownMultiPart = isMultiPart;
                }
                final Pattern pattern = archiveType.buildArchivePattern(filePathParts, isMultiPart);
                if (pattern == null) {
                    continue archiveTypeLoop;
                } else if (isMultiPart != null && archiveType.isMultiPartType() != isMultiPart.booleanValue()) {
                    continue archiveTypeLoop;
                }
                final List<ArchiveFile> foundArchiveFiles = link.createPartFileList(linkPath, pattern.pattern());
                if (foundArchiveFiles == null || foundArchiveFiles.size() == 0) {
                    throw new ArchiveException("Broken archive support!ArchiveType:" + archiveType.name() + "|ArchiveFactory:" + link.getClass().getName() + "|Exists:" + link.exists(allowDeepInspection) + "|Path:" + linkPath + "|Pattern:" + pattern.pattern() + "|MultiPart:" + isMultiPart + "|DeepInspection:" + allowDeepInspection);
                }
                final BitSet availableParts = new BitSet();
                int lowestPartNumber = Integer.MAX_VALUE;
                int partStringLength = 1;
                int highestPartNumber = Integer.MIN_VALUE;
                final int archiveFilesGrow = 128;
                ArchiveFile[] archiveFiles = new ArchiveFile[archiveFilesGrow];
                for (final ArchiveFile archiveFile : foundArchiveFiles) {
                    final String fileName = archiveFile.getName();
                    final String partNumberString = archiveType.getPartNumberString(fileName);
                    final int partNumber = archiveType.getPartNumber(partNumberString);
                    if (partNumber >= 0) {
                        if (partNumberString != null) {
                            partStringLength = Math.max(partStringLength, partNumberString.length());
                        }
                        availableParts.set(partNumber);
                        if (partNumber >= archiveFiles.length) {
                            archiveFiles = Arrays.copyOf(archiveFiles, Math.max(archiveFiles.length + archiveFilesGrow, partNumber + 1));
                        }
                        archiveFiles[partNumber] = archiveFile;
                        if (partNumber < lowestPartNumber) {
                            lowestPartNumber = partNumber;
                        }
                        if (partNumber > highestPartNumber) {
                            highestPartNumber = partNumber;
                        }
                    }
                }
                if (archiveType.looksLikeAnArchive(availableParts, archiveFiles)) {
                    final String[] fileNameParts = archiveType.getMatches(link.getName());
                    final Archive archive = link.createArchive(archiveType);
                    archive.setName(fileNameParts[0]);
                    final String rawID = archiveType.name() + " |" + fileNameParts[0] + archiveType.buildIDPattern(fileNameParts, isMultiPart);
                    final String ID = Hash.getSHA256(rawID);
                    final String archiveID = Archive.getBestArchiveID(foundArchiveFiles, ID);
                    archive.setArchiveID(archiveID);
                    final ArrayList<ArchiveFile> sortedArchiveFiles = new ArrayList<ArchiveFile>();
                    final int minimumParts = Math.max(archiveType.getMinimumNeededPartIndex(), highestPartNumber);
                    for (int partIndex = archiveType.getFirstPartIndex(); partIndex <= minimumParts; partIndex++) {
                        if (availableParts.get(partIndex) == false) {
                            final File missingFile = new File(archiveType.buildMissingPart(filePathParts, partIndex, partStringLength));
                            sortedArchiveFiles.add(new MissingArchiveFile(missingFile.getName(), missingFile.getAbsolutePath()));
                        } else {
                            if (allowDeepInspection && Boolean.FALSE.equals(archiveType.isValidPart(partIndex, archiveFiles[partIndex], false))) {
                                continue archiveTypeLoop;
                            }
                            sortedArchiveFiles.add(archiveFiles[partIndex]);
                        }
                    }
                    archive.setArchiveFiles(sortedArchiveFiles);
                    return archive;
                } else {
                    continue archiveTypeLoop;
                }
            }
        }
        return null;
    }

    public static Archive createArchive(final ArchiveFactory link, final boolean allowDeepInspection) throws ArchiveException {
        return createArchive(link, allowDeepInspection, ArchiveType.values());
    }
}
