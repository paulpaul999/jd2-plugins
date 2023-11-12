package org.jdownloader.gui.views.linkgrabber.quickfilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.Icon;

import jd.controlling.linkcrawler.CrawledLink;

import org.jdownloader.DomainInfo;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.views.components.Header;
import org.jdownloader.gui.views.linkgrabber.LinkGrabberTable;
import org.jdownloader.images.NewTheme;

public class QuickFilterHosterTable extends FilterTable {
    /**
     *
     */
    private static final long       serialVersionUID = 658947589171018284L;
    private HashMap<String, Filter> filterMapping    = new HashMap<String, Filter>();
    private HashMap<String, Filter> enabledFilters   = new HashMap<String, Filter>();

    public QuickFilterHosterTable(Header hosterFilter, LinkGrabberTable table) {
        super(hosterFilter, table, org.jdownloader.settings.staticreferences.CFG_LINKFILTER.LINKGRABBER_HOSTER_QUICKFILTER_ENABLED);
    }

    @Override
    protected FilterTableDataUpdater getFilterTableDataUpdater() {
        return new FilterTableDataUpdater() {
            Set<Filter>   usedFilters        = new HashSet<Filter>();
            AtomicBoolean newDisabledFilters = new AtomicBoolean(false);

            @Override
            public void updateVisible(CrawledLink link) {
                Filter filter = getFilter(link, newDisabledFilters);
                usedFilters.add(filter);
                filter.increaseCounter();
            }

            @Override
            public void updateFiltered(CrawledLink link) {
                usedFilters.add(getFilter(link, newDisabledFilters));
            }

            @Override
            public void reset() {
                for (Filter filter : filterMapping.values()) {
                    filter.resetCounter();
                }
            }

            @Override
            public FilterTable getFilterTable() {
                return QuickFilterHosterTable.this;
            }

            @Override
            public List<Filter> finalizeUpdater() {
                return new ArrayList<Filter>(usedFilters);
            }

            @Override
            public void afterVisible() {
            }

            @Override
            public boolean hasNewDisabledFilters() {
                return newDisabledFilters.get();
            }
        };
    }

    private void setEnabled(boolean enabled, Filter filter, String ID) {
        synchronized (enabledFilters) {
            if (!enabled) {
                enabledFilters.put(ID, filter);
            } else {
                enabledFilters.remove(ID);
            }
        }
        getLinkgrabberTable().getModel().recreateModel(false);
    }

    private String getID(CrawledLink link) {
        final DomainInfo info = link.getDomainInfo();
        if (link.isDirectHTTP()) {
            final String linkHOST = info.getTld();// Browser.getHost(link.getURL());
            return linkHOST != null ? "http_".concat(linkHOST) : "http_unknown";
        } else if (link.isFTP()) {
            final String linkHOST = info.getTld();// Browser.getHost(link.getURL());
            return linkHOST != null ? "ftp_".concat(linkHOST) : "ftp_unknown";
        } else {
            return info.getTld();
        }
    }

    private Filter getFilter(CrawledLink link, AtomicBoolean newDisabledFilters) {
        final DomainInfo info = link.getDomainInfo();
        final String ID;
        final Icon favIcon;
        if (link.isDirectHTTP()) {
            final String HOST = info.getTld();// Browser.getHost(link.getURL());
            if (HOST == null) {
                ID = "http_unknown";
                favIcon = NewTheme.I().getIcon(IconKey.ICON_BROWSE, 16);
            } else {
                ID = "http_".concat(HOST);
                favIcon = null;
            }
        } else if (link.isFTP()) {
            final String HOST = info.getTld();// Browser.getHost(link.getURL());
            if (HOST == null) {
                ID = "ftp_unknown";
                favIcon = NewTheme.I().getIcon(IconKey.ICON_BROWSE, 16);
            } else {
                ID = "ftp_".concat(HOST);
                favIcon = null;
            }
        } else {
            ID = info.getTld();
            favIcon = null;
        }
        Filter ret = filterMapping.get(ID);
        if (ret == null) {
            ret = new Filter(ID, null) {
                protected String getID() {
                    return "Hoster_" + ID;
                }

                @Override
                public Icon getIcon() {
                    final Icon icon = super.getIcon();
                    if (icon == null) {
                        return info;
                    } else {
                        return icon;
                    }
                }

                @Override
                public boolean isFiltered(CrawledLink link) {
                    return ID.equals(QuickFilterHosterTable.this.getID(link));
                }

                @Override
                public void setEnabled(boolean enabled) {
                    super.setEnabled(enabled);
                    QuickFilterHosterTable.this.setEnabled(enabled, this, ID);
                }
            };
            if (favIcon != null) {
                ret.setIcon(favIcon);
            }
            filterMapping.put(ID, ret);
            if (!ret.isEnabled()) {
                newDisabledFilters.set(true);
                synchronized (enabledFilters) {
                    enabledFilters.put(ID, ret);
                }
            }
        }
        return ret;
    }

    @Override
    public boolean isFiltered(CrawledLink e) {
        Filter ret = null;
        synchronized (enabledFilters) {
            ret = enabledFilters.get(getID(e));
        }
        return ret != null && !ret.isEnabled() && ret != getFilterException();
    }

    @Override
    public boolean isFilteringChildrenNodes() {
        synchronized (enabledFilters) {
            return isEnabled() && enabledFilters.size() > 0;
        }
    }

    @Override
    public int getComplexity() {
        return 0;
    }
}
