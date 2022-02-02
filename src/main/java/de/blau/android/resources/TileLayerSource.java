package de.blau.android.resources;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import com.mapbox.geojson.Feature;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.blau.android.App;
import de.blau.android.Logic;
import de.blau.android.Main;
import de.blau.android.contract.FileExtensions;
import de.blau.android.contract.Files;
import de.blau.android.contract.MimeTypes;
import de.blau.android.contract.Urls;
import de.blau.android.imageryoffset.Offset;
import de.blau.android.osm.BoundingBox;
import de.blau.android.osm.OsmXml;
import de.blau.android.osm.Server;
import de.blau.android.osm.ViewBox;
import de.blau.android.prefs.Preferences;
import de.blau.android.resources.KeyDatabaseHelper.EntryType;
import de.blau.android.resources.TileLayerSource.Provider.CoverageArea;
import de.blau.android.resources.bing.Bing;
import de.blau.android.resources.eli.Eli;
import de.blau.android.resources.eli.EliFeatureCollection;
import de.blau.android.services.util.MapTile;
import de.blau.android.services.util.MapTileDownloader;
import de.blau.android.util.Density;
import de.blau.android.util.ExecutorTask;
import de.blau.android.util.FileUtil;
import de.blau.android.util.GeoMath;
import de.blau.android.util.SavingHelper;
import de.blau.android.util.Version;
import de.blau.android.views.layers.MapTilesLayer;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The OpenStreetMapRendererInfo stores information about available tile servers.
 * 
 * This class was taken from OpenStreetMapViewer (original package org.andnav.osm) in 2010-06 by Marcus Wolschon to be
 * integrated into the de.blau.android.osmeditor4android.
 * 
 * @author plusminus on 18:23:16 - 25.09.2008
 * @author Nicolas Gramlich
 * @author Marcus Wolschon Marcus@Wolschon.biz
 * @author Andrew Gregory
 * @author Simon Poole
 *
 */
public class TileLayerSource implements Serializable {
    private static final String DEBUG_TAG = TileLayerSource.class.getSimpleName();

    private static final long serialVersionUID = 4L;

    public static final String EPSG_900913       = "EPSG:900913";
    public static final String EPSG_3857         = "EPSG:3857";
    public static final String EPSG_4326         = "EPSG:4326";
    public static final String TYPE_TMS          = "tms";
    public static final String TYPE_WMS          = "wms";
    static final String        TYPE_WMS_ENDPOINT = "wms_endpoint";
    static final String        TYPE_BING         = "bing";
    static final String        TYPE_SCANEX       = "scanex";
    public static final String LAYER_MAPNIK      = "MAPNIK";
    public static final String LAYER_NONE        = "NONE";
    public static final String LAYER_NOOVERLAY   = "NOOVERLAY";
    public static final String LAYER_BING        = "BING";

    private static final String SWITCH_START = "{switch:";

    private static final String WMS_VERSION_130 = "1.3.0";

    public enum TileType {
        BITMAP, MVT
    }

    /**
     * A tile layer provide has some attribution text, and one or more coverage areas.
     * 
     * @author Andrew Gregory
     */
    public static class Provider {
        /**
         * A coverage area is a range of zooms and a bounding box.
         * 
         * @author Andrew Gregory
         */
        public static class CoverageArea {
            /** Zoom and area of this coverage area. */
            private final int         zoomMin;
            private final int         zoomMax;
            private final BoundingBox bbox;

            /**
             * Construct a new instance from zooms and BoundingBox
             * 
             * @param zoomMin minimum zoom
             * @param zoomMax maximum zoom
             * @param bbox the BoundingBox or null
             */
            public CoverageArea(int zoomMin, int zoomMax, @Nullable BoundingBox bbox) {
                this.zoomMin = zoomMin;
                this.zoomMax = zoomMax;
                this.bbox = bbox;
            }

            /**
             * Test if the given zoom and area is covered by this coverage area.
             * 
             * @param zoom The zoom level to test.
             * @param area The map area to test.
             * @return true if the given zoom and area are covered by this coverage area.
             */
            public boolean covers(int zoom, BoundingBox area) {
                return (zoom >= zoomMin && zoom <= zoomMax && (this.bbox == null || this.bbox.intersects(area)));
            }

            /**
             * Check if this CoverageArea intersects with a BoundingBox
             * 
             * @param area the BoundingBox
             * @return true in the coverages intersect
             */
            public boolean covers(@NonNull BoundingBox area) {
                return this.bbox == null || this.bbox.intersects(area);
            }

            /**
             * Check if a coordinate is covered
             * 
             * @param lon WGS84 longitude
             * @param lat WGS84 latitude
             * @return true if this CoverageArea covers the location
             */
            public boolean covers(double lon, double lat) {
                return this.bbox == null || this.bbox.isIn((int) (lon * 1E7d), (int) (lat * 1E7d));
            }

            /**
             * Get the minimum zoom for this area
             * 
             * @return the minimum zoom
             */
            public int getMinZoomLevel() {
                return zoomMin;
            }

            /**
             * Get the maximum zoom for this area
             * 
             * @return the maximum zoom
             */
            public int getMaxZoomLevel() {
                return zoomMax;
            }

            /**
             * Get the BoundingBox of this CoverageArea
             * 
             * @return a BoundingBox or null if non set
             */
            @Nullable
            public BoundingBox getBoundingBox() {
                return bbox;
            }
        }

        /** Attribution for this provider. */
        private String             attribution;
        /** Attribution URL for this provider. */
        private String             attributionUrl;
        /** Coverage area provided by this provider. */
        private List<CoverageArea> coverageAreas = new ArrayList<>();

        /**
         * Default constructor
         */
        public Provider() {
            // no defaults
        }

        /**
         * Add a CoverageArea to the list
         * 
         * @param ca the CoverageArea to add
         */
        public void addCoverageArea(@NonNull CoverageArea ca) {
            getCoverageAreas().add(ca);
        }

        /**
         * Get the attribution for this provider.
         * 
         * @return The attribution for this provider.
         */
        @Nullable
        public String getAttribution() {
            return attribution;
        }

        /**
         * Set the attribution for this provider
         * 
         * @param attribution the attribution string
         */
        public void setAttribution(@Nullable String attribution) {
            this.attribution = attribution;
        }

        /**
         * Get the attribution URL for this provider.
         * 
         * @return The attribution URL for this provider.
         */
        @Nullable
        public String getAttributionUrl() {
            return attributionUrl;
        }

        /**
         * Set the attribution URL for this provider
         * 
         * @param attributionUrl the attribution URL string
         */
        public void setAttributionUrl(@Nullable String attributionUrl) {
            this.attributionUrl = attributionUrl;
        }

        /**
         * Test if the provider covers the given zoom and area.
         * 
         * @param zoom Zoom level to test.
         * @param area Map area to test.
         * @return true if the provider has coverage of the given zoom and area.
         */
        public boolean covers(int zoom, @NonNull BoundingBox area) {
            if (getCoverageAreas().isEmpty()) {
                return true;
            }
            for (CoverageArea a : getCoverageAreas()) {
                if (a.covers(zoom, area)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Test if the provider covers the area
         * 
         * @param area area to test
         * @return true if the provider has coverage of the given area
         */
        public boolean covers(@NonNull BoundingBox area) {
            if (getCoverageAreas().isEmpty()) {
                return true;
            }
            for (CoverageArea a : getCoverageAreas()) {
                if (a.covers(area)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Get the maximum zoom for this Provider in the provided BoundingBox
         * 
         * @param area the BoundingBox
         * @return the maximum zoom
         */
        public int getZoom(@NonNull BoundingBox area) {
            if (getCoverageAreas().isEmpty()) {
                return -1;
            }
            int max = 0;
            for (CoverageArea a : getCoverageAreas()) {
                if (a.covers(area)) {
                    int m = a.zoomMax;
                    if (m > max) {
                        max = m;
                    }
                }
            }
            return max;
        }

        /**
         * Get the highest zoom CoverageArea for a location
         * 
         * @param lon longitude
         * @param lat latitude
         * @return a CoverageArea or null if none could be found
         */
        @Nullable
        public CoverageArea getCoverageArea(double lon, double lat) {
            CoverageArea result = null;
            for (CoverageArea a : getCoverageAreas()) {
                if (a.covers(lon, lat)) {
                    if (result == null) {
                        result = a;
                    } else {
                        if (a.zoomMax > result.zoomMax) {
                            result = a;
                        }
                    }
                }
                Log.d(DEBUG_TAG, "maxZoom " + a.zoomMax);
            }
            return result;
        }

        /**
         * @return the coverageAreas
         */
        public List<CoverageArea> getCoverageAreas() {
            return coverageAreas;
        }
    }

    public static final int PREFERENCE_DEFAULT = 0;
    public static final int PREFERENCE_BEST    = 10;

    public static final int DEFAULT_MIN_ZOOM     = 0;
    public static final int DEFAULT_MAX_ZOOM     = 18;
    public static final int DEFAULT_WMS_MAX_ZOOM = 22;
    public static final int NO_MAX_ZOOM          = -1;
    public static final int DEFAULT_MAX_OVERZOOM = 4;

    public static final int DEFAULT_TILE_SIZE = 256;
    public static final int WMS_TILE_SIZE     = 512;

    private static final String WMS_AXIS_XY = "XY";
    private static final String WMS_AXIS_YX = "YX";

    public enum Category {
        photo, map, historicmap, osmbasedmap, historicphoto, qa, other, elevation, internal // NOSONAR
    }

    // ===========================================================
    // Fields
    // ===========================================================

    private transient Context        ctx;
    private boolean                  metadataLoaded;
    private String                   id;
    private String                   name;
    private String                   type;
    private Category                 category;
    private String                   source;
    private String                   tileUrl;
    private String                   originalUrl;
    private String                   imageFilenameExtension;
    private String                   touUri;
    private boolean                  overlay;
    private boolean                  defaultLayer;
    private int                      zoomLevelMin;
    private int                      zoomLevelMax;
    private int                      tileWidth;
    private int                      tileHeight;
    private String                   proj;
    private int                      preference;
    private long                     startDate        = -1L;
    private long                     endDate          = Long.MAX_VALUE;
    private int                      maxOverZoom      = DEFAULT_MAX_OVERZOOM; // currently hardwired
    private String                   logoUrl          = null;
    private transient Bitmap         logoBitmap       = null;
    private transient Drawable       logoDrawable     = null;
    private final Queue<String>      subdomains       = new LinkedList<>();
    private int                      defaultAlpha;
    private String                   noTileHeader     = null;
    private String[]                 noTileValues     = null;
    private byte[]                   noTileTile       = null;
    private String                   description      = null;
    private String                   privacyPolicyUrl = null;
    private String                   wmsAxisOrder     = null;
    private transient List<Provider> providers        = new ArrayList<>();
    private TileType                 tileType;

    private boolean  readOnly = false;
    private String   imageryOffsetId; // cached id for offset DB
    private Offset[] offsets;

    private static Map<String, TileLayerSource> backgroundServerList = null;
    private static Map<String, TileLayerSource> overlayServerList    = null;
    private static Object                       serverListLock       = new Object();
    private static boolean                      ready                = false;
    private static List<String>                 imageryBlacklist     = null;

    private static Map<String, Drawable> logoCache = new HashMap<>();
    private static final Drawable        NOLOGO    = new ColorDrawable();

    // ===========================================================
    // Constructors
    // ===========================================================

    /**
     * Load additional data on the source from an URL, potentially async This is mainly used for bing imagery
     * 
     * @param metadataUrl the url for the meta-data
     */
    private void loadMeta(String metadataUrl) {
        try {
            Resources r = ctx.getResources();
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            // Get the tile metadata
            InputStream is = null;
            try {
                if (metadataUrl.startsWith("@raw/")) {
                    // internal URL
                    int resid = r.getIdentifier(metadataUrl.substring(5), "raw", "de.blau.android");
                    is = r.openRawResource(resid);
                } else {
                    // assume Internet URL
                    Request request = new Request.Builder().url(replaceGeneralParameters(metadataUrl)).build();
                    OkHttpClient client = App.getHttpClient().newBuilder().connectTimeout(MapTileDownloader.TIMEOUT, TimeUnit.MILLISECONDS)
                            .readTimeout(MapTileDownloader.TIMEOUT, TimeUnit.MILLISECONDS).build();
                    Call metadataCall = client.newCall(request);
                    Response metadataCallResponse = metadataCall.execute();
                    if (metadataCallResponse.isSuccessful()) {
                        ResponseBody responseBody = metadataCallResponse.body();
                        is = responseBody.byteStream();
                    } else {
                        throw new IOException(metadataCallResponse.message());
                    }
                }
                parser.setInput(is, null);

                // load meta information from Bing (or from other sources using the same format)
                Bing.loadMeta(ctx, this, parser);
            } finally {
                SavingHelper.close(is);
            }
            metadataLoaded = true;
            // once we've got here, a selected layer that was previously non-available might now be available ... reset
            // map preferences
            if (ctx instanceof Main && ((Main) ctx).getMap() != null) { // don't do this in the service
                ((Main) ctx).getMap().setPrefs(ctx, new Preferences(ctx));
            }
        } catch (Exception e) {
            Log.d(DEBUG_TAG, "Tileserver problem metadata URL " + metadataUrl, e);
        }
    }

    /**
     * Retrieve a logo from the Internet
     * 
     * @param brandLogoUri the url
     * @return a scaled BitmapDrawable or null if the logo couldn't be found
     */
    @Nullable
    public BitmapDrawable getLogoFromUrl(@NonNull String brandLogoUri) {
        InputStream bis = null;
        try {
            Request request = new Request.Builder().url(replaceGeneralParameters(brandLogoUri)).build();
            OkHttpClient client = App.getHttpClient().newBuilder().connectTimeout(MapTileDownloader.TIMEOUT, TimeUnit.MILLISECONDS)
                    .readTimeout(MapTileDownloader.TIMEOUT, TimeUnit.MILLISECONDS).build();
            Call logoCall = client.newCall(request);
            Response logoCallResponse = logoCall.execute();
            if (logoCallResponse.isSuccessful()) {
                ResponseBody responseBody = logoCallResponse.body();
                bis = responseBody.byteStream();
                Bitmap brandLogoBitmap = BitmapFactory.decodeStream(bis);
                return scaledBitmap(brandLogoBitmap);
            } else {
                throw new IOException(logoCallResponse.message());
            }
        } catch (IOException e) {
            Log.e(DEBUG_TAG, "getLogoFromUrl using " + brandLogoUri + " got " + e.getMessage());
        } finally {
            SavingHelper.close(bis);
        }
        return null;
    }

    /**
     * Scale logos so that they are max 24dp high
     * 
     * @param bitmap input Bitmap
     * @return a scaled BitmapDrawable
     */
    private BitmapDrawable scaledBitmap(@Nullable Bitmap bitmap) {
        // scale according to density
        if (bitmap != null) {
            int height = bitmap.getHeight();
            int width = bitmap.getWidth();
            float scale = height > 24 ? 24f / height : 1f;
            return new BitmapDrawable(ctx.getResources(),
                    Bitmap.createScaledBitmap(bitmap, Density.dpToPx(ctx, Math.round(width * scale)), Density.dpToPx(ctx, Math.round(height * scale)), false));
        }
        return null;
    }

    /**
     * Construct a new TileLayerServer object from the parameters
     * 
     * @param ctx android context
     * @param id the layer id
     * @param name the layer name
     * @param url the template url for the layer
     * @param type the special types of layer: "bing","scanex"
     * @param category type of imagery
     * @param overlay true if this layer is an overlay
     * @param defaultLayer true if this should be used as the default
     * @param provider a Provider object containing detailed provider information
     * @param termsOfUseUrl a url pointing to terms of use
     * @param icon string containing the logo data, an url or a Android resource name
     * @param logoUrl if not null contains an URL for the logo
     * @param logoBytes if not null contains the bytes of a Bitmap
     * @param zoomLevelMin minimum supported zoom level
     * @param zoomLevelMax maximum supported room level
     * @param maxOverZoom maximum overzoom to allow
     * @param tileWidth width of the tiles in pixels
     * @param tileHeight height of the tiles in pixels
     * @param proj supported projection for WMS servers
     * @param preference relative preference (larger == better)
     * @param startDate start date as a ms since epoch value, -1 if not available
     * @param endDate end date as a ms since epoch value, -1 if not available
     * @param noTileHeader header that indicates that a tile isn't valid
     * @param noTileValues values that together with the header indicated that a tile isn't valid
     * @param description a textual description of the layer or null
     * @param privacyPolicyUrl a link to a privacy policy or null
     * @param async run loadInfo async, needed for main process
     */
    public TileLayerSource(@NonNull final Context ctx, @Nullable final String id, @NonNull final String name, final String url, final String type,
            Category category, final boolean overlay, final boolean defaultLayer, @Nullable final Provider provider, final String termsOfUseUrl,
            final String icon, String logoUrl, byte[] logoBytes, final int zoomLevelMin, final int zoomLevelMax, int maxOverZoom, final int tileWidth,
            final int tileHeight, final String proj, final int preference, final long startDate, final long endDate, @Nullable String noTileHeader,
            @Nullable String[] noTileValues, @Nullable String description, @Nullable String privacyPolicyUrl, boolean async) {

        this.ctx = ctx;
        this.id = id;
        this.name = name;
        this.type = type;
        this.category = category;
        setTileUrl(url);
        originalUrl = url;
        this.overlay = overlay;
        this.defaultLayer = defaultLayer;
        this.zoomLevelMin = zoomLevelMin;
        this.setMaxZoom(zoomLevelMax);
        this.maxOverZoom = maxOverZoom;
        this.setTileWidth(tileWidth);
        this.setTileHeight(tileHeight);
        this.proj = proj;
        this.touUri = termsOfUseUrl;
        this.description = description;
        this.privacyPolicyUrl = privacyPolicyUrl;

        this.offsets = new Offset[zoomLevelMax - zoomLevelMin + 1];
        this.preference = preference;
        this.startDate = startDate;
        this.endDate = endDate;

        this.noTileHeader = noTileHeader;
        this.noTileValues = noTileValues;

        if (provider != null) {
            getProviders().add(provider);
        }

        metadataLoaded = true;

        if (name == null) {
            // parse error or other fatal issue
            this.name = "INVALID";
        }
        if (this.id == null) {
            // generate id from name
            this.id = nameToId(this.name);
        }
        //
        this.id = this.id.toUpperCase(Locale.US);

        if (originalUrl.startsWith(FileUtil.FILE_SCHEME_PREFIX)) { // mbtiles no further processing needed
            readOnly = true;
        }

        // extract switch values
        // this needs to happen before URL parsing, as the ":" will trip things up
        int switchPos = tileUrl.indexOf(SWITCH_START);
        if (switchPos >= 0) {
            int switchEnd = tileUrl.indexOf('}', switchPos);
            if (switchEnd >= 0) {
                String switchValues = tileUrl.substring(switchPos + SWITCH_START.length(), switchEnd);
                Collections.addAll(getSubdomains(), switchValues.split(","));
                StringBuilder t = new StringBuilder(tileUrl);
                setTileUrl(t.replace(switchPos, switchEnd + 1, "{subdomain}").toString());
            }
        }

        String urlPath = null;
        try {
            URL parsedUrl = new URL(tileUrl);
            urlPath = parsedUrl.getPath();
            if (getImageExtension() == null) {
                int extPos = urlPath.lastIndexOf('.');
                if (extPos >= 0) {
                    setImageExtension(urlPath.substring(extPos));
                }
            }
        } catch (MalformedURLException e) {
            Log.e(DEBUG_TAG, "Url parsing failed " + tileUrl + " " + e.getMessage());
        }
        tileType = urlPath != null && (urlPath.endsWith(FileExtensions.MVT) || urlPath.endsWith(FileExtensions.PBF)) ? TileType.MVT : TileType.BITMAP;
        if (TileType.MVT.equals(tileType)) {
            Log.d(DEBUG_TAG, "Tile type " + tileType);
        }

        if (proj != null && tileUrl.contains(MimeTypes.JPEG)) {// wms heuristic
            setImageExtension(FileExtensions.JPG);
        }

        if (icon != null) {
            // String of the format "data:image/png;base64,iV....
            String[] splitString = icon.split(",", 2);
            if (splitString.length == 2 && "data:image/png;base64".equals(splitString[0])) {
                byte[] iconData = Base64.decode(splitString[1], 0);
                logoBitmap = BitmapFactory.decodeByteArray(iconData, 0, iconData.length);
            } else if (icon.startsWith("http")) {
                this.logoUrl = icon;
            }
        } else if (logoUrl != null) {
            this.logoUrl = logoUrl;
        } else if (logoBytes != null) {
            logoBitmap = BitmapFactory.decodeByteArray(logoBytes, 0, logoBytes.length);
        }

        // TODO think of a elegant way to do this
        if (TYPE_BING.equals(type)) {
            metadataLoaded = false;
            if (replaceApiKey(ctx)) { // this will leave the entry in the DB but it will then be ignored
                Log.d(DEBUG_TAG, "bing url " + tileUrl + " async " + async);
                if (async) {
                    new ExecutorTask<String, Void, Void>() {
                        @Override
                        protected Void doInBackground(String url) {
                            loadMeta(url);
                            Log.i(DEBUG_TAG, "Meta-data loaded for layer " + getId());
                            return null;
                        }
                    }.execute(tileUrl);
                } else {
                    loadMeta(tileUrl);
                }
            }
        } else if (TYPE_SCANEX.equals(type)) { // hopelessly hardwired
            setTileUrl("http://irs.gis-lab.info/?layers=" + tileUrl.toLowerCase(Locale.US) + "&request=GetTile&z={zoom}&x={x}&y={y}");
            setImageExtension(FileExtensions.JPG);
        }
    }

    /**
     * Munge a name in to something id like
     * 
     * @param name input name String
     * @return a String with an id
     */
    public static String nameToId(final String name) {
        return name.replaceAll("[\\W\\_]", "").toUpperCase(Locale.US);
    }

    /**
     * Get the default tile layer.
     * 
     * @param ctx Android Context.
     * @param async retrieve meta-data async if true
     * @return The default tile layer.
     */
    public static TileLayerSource getDefault(final Context ctx, final boolean async) {
        // ask for an invalid renderer, so we'll get the fallback default
        return get(ctx, "", async);
    }

    /**
     * Parse a ELI geojson format InputStream for imagery configs and add them to backgroundServerList or
     * overlayServerList
     * 
     * @param ctx android context
     * @param source from which source this config is
     * @param writeableDb SQLiteDatabase
     * @param is InputStream to parse
     * @param async obtain meta data async (bing only)
     * @throws IOException if there was an IO error
     */
    public static void parseImageryFile(@NonNull Context ctx, @NonNull SQLiteDatabase writeableDb, @NonNull String source, @NonNull InputStream is,
            final boolean async) throws IOException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName(OsmXml.UTF_8)));
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        try {
            EliFeatureCollection fc = EliFeatureCollection.fromJson(sb.toString());
            Version formatVersion = fc.formatVersion();
            Log.i(DEBUG_TAG, "Reading imagery configuration version " + (formatVersion == null ? "unknown" : formatVersion.toString()));
            boolean fakeMultiPolygons = formatVersion == null || !formatVersion.largerThanOrEqual(Eli.VERSION_1_1);
            for (Feature f : fc.features()) {
                TileLayerSource osmts = Eli.geojsonToServer(ctx, f, async, fakeMultiPolygons);
                if (osmts != null) {
                    TileLayerDatabase.addLayer(writeableDb, source, osmts);
                } else {
                    Log.w(DEBUG_TAG, "Imagery layer config couldn't be parsed/unsupported");
                }
            }
            TileLayerDatabase.updateSource(writeableDb, source, System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(DEBUG_TAG, "Fatal error parsing " + source + " " + e.getMessage());
        }
    }

    /**
     * Get the tile server information for a specified tile server id. If the given id cannot be found, a default
     * renderer is selected.
     * 
     * Note: will read the the config files it that hasn't happened yet
     * 
     * @param ctx activity context
     * @param id The internal id of the tile layer, eg "MAPNIK"
     * @param async get meta data asynchronously
     * @return the selected TileLayerServer
     */
    @Nullable
    public static TileLayerSource get(@NonNull final Context ctx, @Nullable final String id, final boolean async) {
        synchronized (serverListLock) {
            if (!ready) {
                try (TileLayerDatabase db = new TileLayerDatabase(ctx)) {
                    getLists(ctx, db, async);
                }
                if (imageryBlacklist != null && async) {
                    applyBlacklist(imageryBlacklist);
                }
                ready = true;
            }
        }

        if (id == null || "".equals(id)) { // empty id
            return backgroundServerList.get(LAYER_NONE); // nothing works for all layers :-)
        }

        TileLayerSource overlay = overlayServerList.get(id);
        if (overlay != null) {
            return overlay;
        } else {
            TileLayerSource background = backgroundServerList.get(id);
            if (background != null) {
                return background;
            }
        }
        synchronized (serverListLock) {
            // layer couldn't be found in memory, check database
            Log.d(DEBUG_TAG, "Getting layer " + id + " from database");
            try (TileLayerDatabase db = new TileLayerDatabase(ctx)) {
                TileLayerSource layer = TileLayerDatabase.getLayer(ctx, db.getReadableDatabase(), id);
                if (layer != null && layer.replaceApiKey(ctx)) {
                    if (layer.isOverlay()) {
                        overlayServerList.put(id, layer);
                    } else {
                        backgroundServerList.put(id, layer);
                    }
                    return layer;
                }
            }
            Log.e(DEBUG_TAG, "Layer " + id + " null from database");
        }
        // catch all
        return null;
    }

    /**
     * Read a file from assets containing layer configurations and add them to the database
     * 
     * @param ctx Android Context
     * @param writableDb a writable SQLiteDatabase
     * @param newConfig set to true if we are updating an existing database
     * @param async set this to true if running in the foreground or similar
     */
    public static void createOrUpdateFromAssetsSource(@NonNull final Context ctx, @NonNull SQLiteDatabase writableDb, boolean newConfig, final boolean async) {
        Log.d(DEBUG_TAG, "DB not initalized, parsing configuration files");
        AssetManager assetManager = ctx.getAssets();
        long start = System.currentTimeMillis();
        try {
            writableDb.beginTransaction();
            // entries in earlier files will not be overwritten by later ones
            if (newConfig) {
                // delete old
                TileLayerDatabase.deleteSource(writableDb, TileLayerDatabase.SOURCE_ELI);
                TileLayerDatabase.deleteSource(writableDb, TileLayerDatabase.SOURCE_JOSM_IMAGERY);
                TileLayerDatabase.addSource(writableDb, TileLayerDatabase.SOURCE_JOSM_IMAGERY);
            }
            String[] imageryFiles = { Files.FILE_NAME_VESPUCCI_IMAGERY, Files.FILE_NAME_USER_IMAGERY };
            for (String fn : imageryFiles) {
                try (InputStream is = assetManager.open(fn)) {
                    parseImageryFile(ctx, writableDb, TileLayerDatabase.SOURCE_JOSM_IMAGERY, is, async);
                } catch (IOException e) {
                    Log.e(DEBUG_TAG, "reading conf file " + fn + " got " + e.getMessage());
                    throw e;
                }
            }
            writableDb.setTransactionSuccessful();
        } catch (IOException e) {
            // already logged
        } finally {
            writableDb.endTransaction();
        }
        Log.d(DEBUG_TAG, " elapsed time " + (System.currentTimeMillis() - start) / 1000);
    }

    /**
     * Read a file from the Vespucci directory containing custom layer configurations and add them to the database
     * 
     * @param ctx Android Context
     * @param writeableDb a writable SQLiteDatabase
     * @param async set this to true if running in the foreground or similar
     */
    public static void createOrUpdateCustomSource(@NonNull final Context ctx, @NonNull SQLiteDatabase writeableDb, final boolean async) {
        long lastDatabaseUpdate = TileLayerDatabase.getSourceUpdate(writeableDb, TileLayerDatabase.SOURCE_CUSTOM);
        long lastUpdateTime = 0L;
        try {
            File userImageryFile = new File(FileUtil.getPublicDirectory(), Files.FILE_NAME_USER_IMAGERY);
            Log.i(DEBUG_TAG, "Trying to read custom imagery from " + userImageryFile.getPath());
            lastUpdateTime = userImageryFile.lastModified();
            boolean newConfig = lastUpdateTime > lastDatabaseUpdate;
            if (lastDatabaseUpdate == 0 || newConfig) {
                try {
                    writeableDb.beginTransaction();
                    if (newConfig) {
                        // delete old
                        TileLayerDatabase.deleteSource(writeableDb, TileLayerDatabase.SOURCE_CUSTOM);
                        TileLayerDatabase.addSource(writeableDb, TileLayerDatabase.SOURCE_CUSTOM);
                    }
                    try (InputStream is = new FileInputStream(userImageryFile)) {
                        parseImageryFile(ctx, writeableDb, TileLayerDatabase.SOURCE_CUSTOM, is, async);
                    }
                    writeableDb.setTransactionSuccessful();
                } finally {
                    writeableDb.endTransaction();
                }
            }
        } catch (IOException e) {
            Log.i(DEBUG_TAG, "no custom conf files found");
        }
    }

    /**
     * Read a file from either JOSM or ELI sources containing layer configurations and update the database with them
     * 
     * @param ctx Android Context
     * @param writeableDb a writable SQLiteDatabase
     * @param eli if true use ELI
     * @throws IOException if there was an IO error
     */
    public static void updateImagery(@NonNull final Context ctx, @NonNull SQLiteDatabase writeableDb, boolean eli) throws IOException {
        Log.d(DEBUG_TAG, "Updating from imagery sources");
        AssetManager assetManager = ctx.getAssets();
        try {
            writeableDb.beginTransaction();
            // delete old
            TileLayerDatabase.deleteSource(writeableDb, TileLayerDatabase.SOURCE_ELI);
            TileLayerDatabase.deleteSource(writeableDb, TileLayerDatabase.SOURCE_JOSM_IMAGERY);

            String source = eli ? TileLayerDatabase.SOURCE_ELI : TileLayerDatabase.SOURCE_JOSM_IMAGERY;
            TileLayerDatabase.addSource(writeableDb, source);

            // still need to read our base config first
            try (InputStream is = assetManager.open(Files.FILE_NAME_VESPUCCI_IMAGERY)) {
                parseImageryFile(ctx, writeableDb, source, is, true);
            } catch (IOException e) {
                Log.e(DEBUG_TAG, "reading conf files got " + e.getMessage());
            }
            InputStream is = null;
            try {
                Request request = new Request.Builder().url(eli ? Urls.ELI : Urls.JOSM_IMAGERY).build();
                OkHttpClient client = App.getHttpClient().newBuilder().connectTimeout(Server.TIMEOUT, TimeUnit.MILLISECONDS)
                        .readTimeout(Server.TIMEOUT, TimeUnit.MILLISECONDS).build();
                Call josmImageryCall = client.newCall(request);
                Response josmImageryCallResponse = josmImageryCall.execute();
                if (josmImageryCallResponse.isSuccessful()) {
                    ResponseBody responseBody = josmImageryCallResponse.body();
                    is = responseBody.byteStream();
                    parseImageryFile(ctx, writeableDb, source, is, true);
                    writeableDb.setTransactionSuccessful();
                    getListsLocked(ctx, writeableDb, true);
                } else {
                    throw new IOException(josmImageryCallResponse.message());
                }
            } finally {
                SavingHelper.close(is);
            }
        } finally {
            writeableDb.endTransaction();
        }
        MapTilesLayer<?> layer = App.getLogic().getMap().getBackgroundLayer();
        if (layer != null) {
            layer.getTileProvider().update();
        }
    }

    /**
     * Set the in memory lists from the database
     * 
     * @param ctx Android Context
     * @param db an instance of TileLayerDatabase
     * @param populate flag that indicates if we should fully populate the lists or not
     */
    private static void getLists(@NonNull final Context ctx, @NonNull TileLayerDatabase db, boolean populate) {
        getLists(ctx, db.getReadableDatabase(), populate);
    }

    /**
     * Set the in memory lists from the database
     * 
     * @param ctx Android Context
     * @param db the SQLiteDatabase
     * @param populate flag that indicates if we should fully populate the lists or not
     */
    private static void getLists(@NonNull final Context ctx, @NonNull SQLiteDatabase db, boolean populate) {
        long start = System.currentTimeMillis();
        if (populate) {
            overlayServerList = TileLayerDatabase.getAllLayers(ctx, db, true);
            backgroundServerList = TileLayerDatabase.getAllLayers(ctx, db, false);
        } else {
            overlayServerList = new HashMap<>();
            backgroundServerList = new HashMap<>();
            // these three layers have to exist or else we are borked
            TileLayerSource overlay = TileLayerDatabase.getLayer(ctx, db, LAYER_NOOVERLAY);
            overlayServerList.put(LAYER_NOOVERLAY, overlay);
            TileLayerSource background = TileLayerDatabase.getLayer(ctx, db, LAYER_NONE);
            overlayServerList.put(LAYER_NONE, background);
            background = TileLayerDatabase.getLayer(ctx, db, LAYER_MAPNIK);
            overlayServerList.put(LAYER_MAPNIK, background);
        }
        Log.d(DEBUG_TAG, "Generating TileLayer lists took " + (System.currentTimeMillis() - start) / 1000);
    }

    /**
     * Set the in memory lists from the database, locks against concurrent change
     * 
     * @param ctx Android Context
     * @param db the SQLiteDatabase
     * @param populate flag that indicates if we should fully populate the lists or not
     */
    public static void getListsLocked(@NonNull final Context ctx, @NonNull SQLiteDatabase db, boolean populate) {
        synchronized (serverListLock) {
            getLists(ctx, db, populate);
        }
    }

    // ===========================================================
    // Methods
    // ===========================================================

    /**
     * Check if the meta data for this layer is loaded
     * 
     * @return true if the meta data is loaded
     */
    public boolean isMetadataLoaded() {
        return metadataLoaded;
    }

    /**
     * Get the Tile layer ID.
     * 
     * @return Tile layer ID.
     */
    @NonNull
    public String getId() {
        return id;
    }

    /**
     * Get the tile width.
     * 
     * @return The tile width in pixels.
     */
    public int getTileWidth() {
        checkMetaData();
        return tileWidth;
    }

    /**
     * Get the tile height.
     * 
     * @return The tile height in pixels.
     */
    public int getTileHeight() {
        checkMetaData();
        return tileHeight;
    }

    /**
     * Get the minimum zoom level these tiles are available at.
     * 
     * @return Minimum zoom level for which the tile layer is available.
     */
    public int getMinZoomLevel() {
        checkMetaData();
        return zoomLevelMin;
    }

    /**
     * Throw an exception if the meta-data isn0t loaded
     */
    private void checkMetaData() {
        if (!metadataLoaded) {
            throw new IllegalStateException("metadata not loaded");
        }
    }

    /**
     * Get the maximum zoom level these tiles are available at.
     * 
     * @return Maximum zoom level for which the tile layer is available.
     */
    public int getMaxZoomLevel() {
        checkMetaData();
        // if (providers != null && providers.size() > 0) {
        // zoomLevelMax = 0;
        // BoundingBox bbox = Application.mainActivity.getMap().getViewBox();
        // for (Provider p:providers) {
        // Provider.CoverageArea ca = p.getCoverageArea((bbox.getLeft() + bbox.getWidth()/2)/1E7d, bbox.getCenterLat());
        // if (ca != null && ca.zoomMax > zoomLevelMax)
        // zoomLevelMax = ca.zoomMax;
        // Log.d("OpenStreetMapTileServer","Provider " + p.getAttribution() + " max zoom " + zoomLevelMax);
        // }
        // }
        return getMaxZoom();
    }

    /**
     * Get the branding logo for the tile layer.
     * 
     * Retrieves logos for which we have an url asynchronously
     * 
     * @return The branding logo, or null if there is none.
     */
    @Nullable
    public Drawable getLogoDrawable() {
        checkMetaData();
        /**
         * We have an url but haven't got the logo yet retrieve it now for use on next redraw
         */
        if (logoDrawable == null && (logoUrl != null || logoBitmap != null)) {
            if (logoBitmap != null) {
                setLogoDrawable(scaledBitmap(logoBitmap));
            } else {
                Logic logic = App.getLogic();
                // this may be called before Logic has been instantiated
                // simply try later
                if (logic == null) {
                    return null;
                }
                new ExecutorTask<Void, Void, Void>(logic.getExecutorService(), logic.getHandler()) {
                    @Override
                    protected Void doInBackground(Void param) {
                        synchronized (TileLayerSource.this) {
                            Drawable cached = logoCache.get(logoUrl);
                            if (cached != NOLOGO && logoUrl != null) { // recheck logoURl
                                if (cached != null) {
                                    setLogoDrawable(cached);
                                } else {
                                    Log.d(DEBUG_TAG, "getLogoDrawable logoUrl " + logoUrl);
                                    setLogoDrawable(getLogoFromUrl(logoUrl));
                                    if (logoDrawable == null) {
                                        logoCache.put(logoUrl, NOLOGO);
                                    } else {
                                        logoCache.put(logoUrl, logoDrawable);
                                    }
                                }
                                logoUrl = null;
                            }
                        }
                        return null;
                    }
                }.execute();
            }
        }
        return logoDrawable;
    }

    /**
     * Zap the cached logo (necessary when screen resolution changes)
     */
    private void clearLogoDrawable() {
        setLogoDrawable(null);
    }

    /**
     * Clear all cached logos
     */
    public static void clearLogos() {
        if (backgroundServerList != null) {
            for (TileLayerSource tls : backgroundServerList.values()) {
                if (tls != null) {
                    tls.clearLogoDrawable();
                }
            }
        }
        if (overlayServerList != null) {
            for (TileLayerSource tls : overlayServerList.values()) {
                if (tls != null) {
                    tls.clearLogoDrawable();
                }
            }
        }
    }

    /**
     * Get the attributions that apply to the given map display.
     * 
     * @param zoom Zoom level of the display.
     * @param area Displayed area to get the attributions of.
     * @return Collections of attributions that apply to the specified area and zoom.
     */
    @NonNull
    public Collection<String> getAttributions(final int zoom, @NonNull final BoundingBox area) {
        checkMetaData();
        Collection<String> ret = new ArrayList<>();
        for (Provider p : getProviders()) {
            if (p.getAttribution() != null && p.covers(Math.min(zoom, getMaxZoom()), area)) { // ignore overzoom
                ret.add(p.getAttribution());
            }
        }
        return ret;
    }

    /**
     * Get the Providers of the given map display.
     * 
     * @param zoom Zoom level of the display.
     * @param area Displayed area to get the attributions of.
     * @return Collections of Providers that apply to the specified area and zoom.
     */
    @NonNull
    public Collection<Provider> getProviders(final int zoom, @NonNull final BoundingBox area) {
        checkMetaData();
        Collection<Provider> ret = new ArrayList<>();
        for (Provider p : providers) {
            if (p.getAttribution() != null && p.covers(Math.min(zoom, getMaxZoom()), area)) { // ignore overzoom
                ret.add(p);
            }
        }
        return ret;
    }

    /**
     * Get the End User Terms Of Use URI.
     * 
     * @return The End User Terms Of Use URI.
     */
    public String getTouUri() {
        return touUri;
    }

    /**
     * Get the offset in WGS84*E7 coordinates
     * 
     * @param zoomLevel the zoom level we want the offset for
     * @return offset in WGS84, null == no offset (or other issue)
     */
    @Nullable
    public Offset getOffset(int zoomLevel) {
        if (zoomLevel < zoomLevelMin) {
            return null;
        }
        int length = offsets.length;
        if (zoomLevel > getMaxZoom()) {
            int index = getMaxZoom() - zoomLevelMin;
            if (index < length) {
                return offsets[index];
            } else {
                return null;
            }
        }
        int index = zoomLevel - zoomLevelMin;
        if (index < length) {
            return offsets[index];
        }
        return null;
    }

    /**
     * Set the lat offset for one specific zoom
     * 
     * @param zoomLevel zoom level to set the offset for
     * @param offsetLon offset in lon direction in WGS84
     * @param offsetLat offset in lat direction in WGS84
     */
    public void setOffset(int zoomLevel, double offsetLon, double offsetLat) {
        Log.d(DEBUG_TAG, "setOffset " + zoomLevel + " " + offsetLon + " " + offsetLat);
        zoomLevel = Math.max(zoomLevel, zoomLevelMin); // clamp to min/max values
        zoomLevel = Math.min(zoomLevel, getMaxZoom());
        if (offsets[zoomLevel - zoomLevelMin] == null) {
            offsets[zoomLevel - zoomLevelMin] = new Offset();
        }
        offsets[zoomLevel - zoomLevelMin].setDeltaLon(offsetLon);
        offsets[zoomLevel - zoomLevelMin].setDeltaLat(offsetLat);
    }

    /**
     * Set the offset for all zoom levels
     * 
     * @param offsetLon offset in lon direction in WGS84
     * @param offsetLat offset in lat direction in WGS84
     */
    public void setOffset(double offsetLon, double offsetLat) {
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] == null) {
                offsets[i] = new Offset();
            }
            offsets[i].setDeltaLon(offsetLon);
            offsets[i].setDeltaLat(offsetLat);
        }
    }

    /**
     * Set the offset for a range of zoom levels
     * 
     * @param startZoom start of zoom range
     * @param endZoom end of zoom range
     * @param offsetLon offset in lon direction in WGS84
     * @param offsetLat offset in lat direction in WGS84
     */
    public void setOffset(int startZoom, int endZoom, double offsetLon, double offsetLat) {
        for (int z = startZoom; z <= endZoom; z++) {
            setOffset(z, offsetLon, offsetLat);
        }
    }

    /**
     * Get the current Offsets for this layer
     * 
     * @return an array of Offsets
     */
    @NonNull
    public Offset[] getOffsets() {
        return offsets;
    }

    /**
     * Set the Offsets for this layer
     * 
     * @param offsets an array of Offset to set
     */
    public void setOffsets(@NonNull Offset[] offsets) {
        this.offsets = offsets;
    }

    /**
     * Return the name for this layer
     * 
     * @return the current name
     */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * Set the name for this layer
     * 
     * @param name the name to use
     */
    public void setName(@NonNull String name) {
        this.name = name;
    }

    /**
     * Check if this layer is "read only" aka a mbtiles file
     * 
     * @return true if read only
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Return a sorted list of tile servers
     * 
     * Takes the preference, end date and relative coverage area size in to account
     * 
     * @param filtered if true only return those layers with a coverage area that overlaps with the supplied bounding
     *            box
     * @param servers input list of servers to sort and potentially filter
     * @param category category of layer that should be returned or null for all
     * @param tileType type of tiles provided by the source
     * @param box bounding box that we are interested in
     * @return list of tile servers
     */
    @NonNull
    private static List<TileLayerSource> getServersFilteredSorted(boolean filtered, @NonNull Map<String, TileLayerSource> servers, @Nullable Category category,
            TileType tileType, @Nullable BoundingBox box) {
        TileLayerSource noneLayer = null;
        List<TileLayerSource> list = new ArrayList<>();
        for (TileLayerSource osmts : servers.values()) {
            if (Category.internal.equals(osmts.getCategory())) {
                // never return internal configs
                continue;
            }
            if (filtered) {
                if (category != null && !category.equals(osmts.getCategory())) {
                    continue;
                }
                if (tileType != null && !tileType.equals(osmts.getTileType())) {
                    continue;
                }
                if (box != null && !osmts.covers(box)) {
                    continue;
                }
            }
            // add this after sorting
            if (LAYER_NONE.equals(osmts.id) || LAYER_NOOVERLAY.equals(osmts.id)) {
                noneLayer = osmts;
                continue;
            }
            // add the rest now
            list.add(osmts);
        }
        // sort according to preference, end date and default layer flag, bb size and name
        Collections.sort(list, (t1, t2) -> {
            if (t1.preference < t2.preference) {
                return 1;
            } else if (t1.preference > t2.preference) {
                return -1;
            }
            if (t1.defaultLayer != t2.defaultLayer) {
                return t2.defaultLayer ? 1 : -1;
            }
            double t1Size = coverageSize(t1.getCoverage());
            double t2Size = coverageSize(t2.getCoverage());
            if (t1Size != t2Size) {
                return t1Size < t2Size ? -1 : 1;
            } else {
                if (t1.endDate != t2.endDate) {
                    // assumption no end date == ongoing
                    return t1.endDate < t2.endDate ? 1 : -1;
                }
                return t1.getName().compareToIgnoreCase(t2.getName()); // alphabetic
            }
        });
        // add NONE
        if (noneLayer != null) {
            list.add(0, noneLayer);
        }
        return list;
    }

    /**
     * Calculate the coverage size in WGS84 degrees^2
     * 
     * @param areas List of ConverageAreas
     * @return an approximate size value in WGS84 degrees^2
     */
    private static double coverageSize(@Nullable List<CoverageArea> areas) {
        double result = 0;
        if (areas != null) {
            for (CoverageArea area : areas) {
                BoundingBox box = area.getBoundingBox();
                if (box != null) {
                    result = +box.getWidth() / 1E7D * box.getHeight() / 1E7D;
                }
            }
        }
        return result == 0 ? GeoMath.MAX_LON * GeoMath.MAX_COMPAT_LAT * 4 : result;
    }

    /**
     * Test if the bounding box is covered by this tile source
     * 
     * @param box the bounding box we want to test
     * @return true if covered or no coverage information
     */
    public boolean covers(@NonNull BoundingBox box) {
        if (!getProviders().isEmpty()) {
            for (Provider p : getProviders()) {
                if (p.covers(box)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Get location dependent max zoom
     * 
     * @param box the bounding box we want to get the max zoom for
     * @return maximum zoom for this area, -1 if nothing found
     */
    public int getMaxZoom(@NonNull BoundingBox box) {
        int max = 0;
        if (!getProviders().isEmpty()) {
            for (Provider p : getProviders()) {
                int m = p.getZoom(box);
                if (m > max) {
                    max = m;
                }
            }
            return max;
        }
        return -1;
    }

    /**
     * Get all the available tile layer IDs.
     * 
     * @param box bounding box to test coverage against
     * @param filtered only return servers that overlap/intersect with the bounding box
     * @param category category of layer that should be returned or null for all
     * @param tileType the type of tiles served by this source
     * @return available tile layer IDs.
     */
    @NonNull
    public static String[] getIds(@Nullable BoundingBox box, boolean filtered, @Nullable Category category, @Nullable TileType tileType) {
        return getIds(backgroundServerList, box, filtered, tileType, category);
    }

    /**
     * Get a, potentially filtered, list of layer IDs
     * 
     * @param serverList Map containing the layers to filter
     * @param box bounding box to test coverage against
     * @param filtered only return servers that overlap/intersect with the bounding box
     * @param tileType the type of tiles served by this source
     * @param category category of layer that should be returned or null for all
     * @return available tile layer IDs.
     */
    private static String[] getIds(@NonNull Map<String, TileLayerSource> serverList, @Nullable BoundingBox box, boolean filtered, @Nullable TileType tileType,
            @Nullable Category category) {
        List<String> ids = new ArrayList<>();
        synchronized (serverListLock) {
            if (backgroundServerList != null) {
                List<TileLayerSource> list = getServersFilteredSorted(filtered, serverList, category, tileType, box);
                for (TileLayerSource t : list) {
                    ids.add(t.id);
                }
            }
        }
        String[] idArray = new String[ids.size()];
        ids.toArray(idArray);
        return idArray;
    }

    /**
     * Get all the available tile layer names.
     * 
     * @param box bounding box to test coverage against
     * @param filtered only return servers that overlap/intersect with the bounding box
     * @return available tile layer names.
     */
    @NonNull
    public static String[] getNames(@Nullable BoundingBox box, boolean filtered) {
        return getNames(backgroundServerList, box, filtered);
    }

    /**
     * Get all the available tile layer names.
     * 
     * @param map Map containing the layers to filter
     * @param box bounding box to test coverage against
     * @param filtered only return servers that overlap/intersect with the bounding box
     * @return available tile layer names.
     */
    @NonNull
    public static String[] getNames(@Nullable Map<String, TileLayerSource> map, @Nullable BoundingBox box, boolean filtered) {
        ArrayList<String> names = new ArrayList<>();
        if (map != null) {
            for (String key : getIds(box, filtered, null, null)) {
                TileLayerSource osmts = map.get(key);
                names.add(osmts.name);
            }
        }
        return names.toArray(new String[names.size()]);
    }

    /**
     * Get tile server names from list of ids
     * 
     * @param ids array containing the ids
     * @return array containing the names
     */
    @NonNull
    public static String[] getNames(@NonNull String[] ids) {
        return getNames(backgroundServerList, ids);
    }

    /**
     * Get tile server names from list of ids
     * 
     * @param map Map containing with id to layer mapping
     * @param ids array containing the ids
     * @return array containing the names
     */
    @NonNull
    public static String[] getNames(@Nullable Map<String, TileLayerSource> map, @NonNull String[] ids) {
        List<String> names = new ArrayList<>();
        if (map != null) {
            for (String key : ids) {
                TileLayerSource osmts = map.get(key);
                names.add(osmts.name + (TYPE_WMS.equals(osmts.type) ? " [wms]" : ""));
            }
        }
        return names.toArray(new String[names.size()]);
    }

    /**
     * Get all the available overlay tile layer IDs.
     * 
     * @param box bounding box to test coverage against
     * @param filtered only return servers that overlap/intersect with the bounding box
     * @param category the category to retrieve or null for all
     * @param tileType the type of tiles served by this source
     * @return available tile layer IDs.
     */
    @NonNull
    public static String[] getOverlayIds(@Nullable BoundingBox box, boolean filtered, @Nullable Category category, @Nullable TileType tileType) {
        return getIds(overlayServerList, box, filtered, tileType, category);
    }

    /**
     * Get all the available overlay tile layer names.
     * 
     * @param box bounding box to test coverage against
     * @param filtered only return servers that overlap/intersect with the bounding box
     * @return available tile layer names.
     */
    @NonNull
    public static String[] getOverlayNames(@Nullable BoundingBox box, boolean filtered) {
        return getNames(overlayServerList, box, filtered);
    }

    /**
     * Get tile server names from list of ids
     * 
     * @param ids id list
     * @return list of names
     */
    @NonNull
    public static String[] getOverlayNames(@NonNull String[] ids) {
        return getNames(overlayServerList, ids);
    }

    /**
     * Replace a parameter of form {param} in a string
     * 
     * Slow and only for use with parameters that don't need to be set more than once
     * 
     * @param s input String
     * @param param parameter to replace
     * @param value value to replace param with
     * @return the string with the parameter replaced
     */
    private static String replaceParameter(@NonNull final String s, @NonNull final String param, @NonNull final String value) {
        String result = s;
        // replace "${param}"
        // not used in imagery index result = result.replaceFirst("\\$\\{" + param + "\\}", value);
        // replace "$param"
        // not used in imagery index result = result.replaceFirst("\\$" + param, value);
        // replace "{param}"
        result = result.replaceFirst("\\{" + param + "\\}", value);
        return result;
    }

    /**
     * Replace some specific parameters that we use. Currently just culture
     * 
     * @param s the input string
     * @return the string with replaced parameters
     */
    public String replaceGeneralParameters(@NonNull final String s) {
        Resources r = ctx.getResources();
        final Locale l = r.getConfiguration().locale;
        String result = s;
        result = replaceParameter(result, "culture", l.getLanguage().toLowerCase(Locale.US) + "-" + l.getCountry().toLowerCase(Locale.US));
        return result;
    }

    private static final int BASE_STATE  = 0;
    private static final int PARAM_STATE = 1;

    /**
     * Allocate the following just once
     */
    StringBuilder builder    = new StringBuilder(100); // 100 is just an estimate to avoid re-allocating
    StringBuilder param      = new StringBuilder();
    StringBuilder quadKey    = new StringBuilder();
    StringBuilder boxBuilder = new StringBuilder();

    /**
     * Get the URL that can be used to obtain the image of the given tile.
     * 
     * This is 5-100 times faster than the previous implementation.
     * 
     * @param aTile The tile to get the URL for.
     * @return URL of the given tile.
     */
    @NonNull
    public synchronized String getTileURLString(@NonNull final MapTile aTile) {
        checkMetaData();
        builder.setLength(0);
        int state = BASE_STATE;
        for (char c : tileUrl.toCharArray()) {
            if (state == BASE_STATE) {
                if (c == '{') {
                    state = PARAM_STATE;
                    param.setLength(0); // reset
                } else {
                    builder.append(c);
                }
            } else {
                if (c == '}') {
                    state = BASE_STATE;
                    String p = param.toString();
                    switch (p) {
                    case "x":
                        builder.append(Integer.toString(aTile.x));
                        break;
                    case "y":
                        builder.append(Integer.toString(aTile.y));
                        break;
                    case "z":
                        builder.append(Integer.toString(aTile.zoomLevel));
                        break;
                    case "zoom":
                        builder.append(Integer.toString(aTile.zoomLevel));
                        break;
                    case "ty":
                    case "-y":
                        int ymax = 1 << aTile.zoomLevel;
                        int y = ymax - aTile.y - 1;
                        builder.append(Integer.toString(y));
                        break;
                    case "quadkey":
                        builder.append(quadTree(aTile));
                        break;
                    case "subdomain":
                        // Rotate through the list of sub-domains
                        String subdomain = null;
                        synchronized (getSubdomains()) {
                            subdomain = getSubdomains().poll();
                            if (subdomain != null) {
                                getSubdomains().add(subdomain);
                            }
                        }
                        if (subdomain != null) {
                            builder.append(subdomain);
                        }
                        break;
                    case "proj": // WMS support from here on
                        builder.append(proj);
                        break;
                    case "width":
                        builder.append(Integer.toString(tileWidth));
                        break;
                    case "height":
                        builder.append(Integer.toString(tileHeight));
                        break;
                    case "bbox":
                        builder.append(wmsBox(aTile));
                        break;
                    default:
                        Log.e(DEBUG_TAG, "Unknown place holder " + p);
                    }
                } else {
                    param.append(c);
                }
            }
        }
        return builder.toString();
    }

    /**
     * Converts TMS tile coordinates to QuadTree
     * 
     * @param aTile The tile coordinates to convert
     * @return The QuadTree as String.
     */
    String quadTree(final MapTile aTile) {
        quadKey.setLength(0);
        for (int i = aTile.zoomLevel; i > 0; i--) {
            int digit = 0;
            int mask = 1 << (i - 1);
            if ((aTile.x & mask) != 0) {
                digit += 1;
            }
            if ((aTile.y & mask) != 0) {
                digit += 2;
            }
            quadKey.append(digit);
        }
        return quadKey.toString();
    }

    /**
     * Converts TMS tile coordinates to WMS bounding box for EPSG:3857/900913 and EPSG:4326
     * 
     * As side effects this will extract the projection from the url if not already set and determine if we need to flip
     * the axis
     * 
     * @param aTile The tile coordinates to convert
     * @return a WMS bounding box string
     */
    String wmsBox(@NonNull final MapTile aTile) {
        boxBuilder.setLength(0);
        if (proj != null) {
            switch (proj) {
            case EPSG_3857:
            case EPSG_900913:
                int ymax = 1 << aTile.zoomLevel;
                int y = ymax - aTile.y - 1;
                boxBuilder.append(GeoMath.tile2lonMerc(tileWidth, aTile.x, aTile.zoomLevel)).append(',');
                boxBuilder.append(GeoMath.tile2latMerc(tileHeight, y, aTile.zoomLevel)).append(',');
                boxBuilder.append(GeoMath.tile2lonMerc(tileWidth, aTile.x + 1, aTile.zoomLevel)).append(',');
                boxBuilder.append(GeoMath.tile2latMerc(tileHeight, y + 1, aTile.zoomLevel));
                break;
            case EPSG_4326:
                if (wmsAxisOrder == null) {
                    // fix craziness that WMS servers >= version 1.3 use the ordering of the axis
                    // in the EPSG definition, which means it changes for 4326
                    Pattern pat = Pattern.compile("[\\?\\&]version=([0-9\\.]+)", Pattern.CASE_INSENSITIVE);
                    Matcher matcher = pat.matcher(originalUrl);
                    if (matcher.find()) {
                        String versionStr = matcher.group(1);
                        if (versionStr != null) {
                            Version version = new Version(versionStr);
                            wmsAxisOrder = version.largerThanOrEqual(WMS_VERSION_130) ? WMS_AXIS_YX : WMS_AXIS_XY;
                        }
                    }
                }
                // note this is hack that simply squashes the vertical axis to fit to square tiles
                if (WMS_AXIS_XY.equals(wmsAxisOrder)) {
                    boxBuilder.append(GeoMath.tile2lon(aTile.x, aTile.zoomLevel)).append(',');
                    boxBuilder.append(GeoMath.tile2lat(aTile.y + 1, aTile.zoomLevel)).append(',');
                    boxBuilder.append(GeoMath.tile2lon(aTile.x + 1, aTile.zoomLevel)).append(',');
                    boxBuilder.append(GeoMath.tile2lat(aTile.y, aTile.zoomLevel));
                } else {
                    boxBuilder.append(GeoMath.tile2lat(aTile.y + 1, aTile.zoomLevel)).append(',');
                    boxBuilder.append(GeoMath.tile2lon(aTile.x, aTile.zoomLevel)).append(',');
                    boxBuilder.append(GeoMath.tile2lat(aTile.y, aTile.zoomLevel)).append(',');
                    boxBuilder.append(GeoMath.tile2lon(aTile.x + 1, aTile.zoomLevel));
                }
                break;
            default:
                Log.e(DEBUG_TAG, "Unsupported projection " + proj + " for " + getName());
            }
        } else {
            // set proj from url &SRS=EPSG:4326 or &CRS=EPSG:4326
            Pattern pat = Pattern.compile("[\\?\\&][sc]rs=(EPSG:[0-9]+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pat.matcher(originalUrl);
            if (matcher.find()) {
                String projParameter = matcher.group(1);
                if (projParameter != null) {
                    proj = projParameter.toUpperCase(Locale.US);
                    Log.i(DEBUG_TAG, "Extracted " + proj + " from layer " + getName());
                    return wmsBox(aTile);
                }
            }
            Log.e(DEBUG_TAG, "No projection for layer " + getName());
        }
        return boxBuilder.toString();
    }

    /**
     * Get the maximum we over zoom for this layer
     * 
     * @return the maximum number of additional zoom levels we overzoom
     */
    public int getMaxOverZoom() {
        return maxOverZoom;
    }

    /**
     * This is essentially the code in in the reference implementation see
     * 
     * https://trac.openstreetmap.org/browser/subversion/applications/editors/josm/plugins/imagery_offset_db/src/iodb/ImageryIdGenerator.java#L24
     * 
     * @return the id for a imagery offset database query
     */
    public String getImageryOffsetId() {
        if (imageryOffsetId != null) {
            return imageryOffsetId;
        }
        String url = originalUrl;
        if (url == null) {
            return null;
        }

        // predefined layers
        if (id.equals(LAYER_BING)) {
            return TYPE_BING;
        }

        if (url.contains("irs.gis-lab.info")) {
            return "scanex_irs";
        }

        if ("Mapbox".equalsIgnoreCase(id)) {
            return "mapbox";
        }

        // Remove protocol
        int i = url.indexOf("://");
        if (i == -1) { // TODO more sanity checks
            return "invalid_URL";
        }
        url = url.substring(i + 3);

        // Split URL into address and query string
        i = url.indexOf('?');
        String query = "";
        if (i > 0) {
            query = url.substring(i);
            url = url.substring(0, i);
        }

        TreeMap<String, String> qparams = new TreeMap<>();
        String[] qparamsStr = query.length() > 1 ? query.substring(1).split("&") : new String[0];
        for (String p : qparamsStr) {
            String[] kv = p.split("=");
            kv[0] = kv[0].toLowerCase(Locale.US);
            // TMS: skip parameters with variable values and Mapbox's access token
            if ((kv.length > 1 && kv[1].indexOf('{') >= 0 && kv[1].indexOf('}') > 0) || "access_token".equals(kv[0])) {
                continue;
            }
            qparams.put(kv[0].toLowerCase(Locale.US), kv.length > 1 ? kv[1] : null);
        }

        // Reconstruct query parameters
        StringBuilder sb = new StringBuilder();
        for (Entry<String, String> qk : qparams.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            } else if (query.length() > 0) {
                sb.append('?');
            }
            sb.append(qk.getKey()).append('=').append(qk.getValue());
        }
        query = sb.toString();

        // TMS: remove /{zoom} and /{y}.png parts
        url = url.replaceAll("\\/\\{[^}]+\\}(?:\\.\\w+)?", "");
        // TMS: remove variable parts
        url = url.replaceAll("\\{[^}]+\\}", "");
        while (url.contains("..")) {
            url = url.replace("..", ".");
        }
        if (url.startsWith(".")) {
            url = url.substring(1);
        }
        imageryOffsetId = url + query;

        return imageryOffsetId;
    }

    /**
     * Remove all background and overlay entries that match the supplied blacklist
     * 
     * @param blacklist list of servers that should be removed
     */
    public static void applyBlacklist(List<String> blacklist) {
        // first compile the regexs
        List<Pattern> patterns = new ArrayList<>();
        for (String regex : blacklist) {
            patterns.add(Pattern.compile(regex));
        }
        synchronized (serverListLock) {
            for (Pattern p : patterns) {
                if (backgroundServerList != null) {
                    for (String key : new TreeSet<>(backgroundServerList.keySet())) { // shallow copy
                        TileLayerSource osmts = backgroundServerList.get(key);
                        Matcher m = p.matcher(osmts.tileUrl.toLowerCase(Locale.US));
                        if (m.find()) {
                            backgroundServerList.remove(key);
                            Log.d(DEBUG_TAG, "Removed background tile layer " + key);
                        }
                    }
                }
                if (overlayServerList != null) {
                    for (String key : new TreeSet<>(overlayServerList.keySet())) { // shallow copy
                        TileLayerSource osmts = overlayServerList.get(key);
                        Matcher m = p.matcher(osmts.tileUrl.toLowerCase(Locale.US));
                        if (m.find()) {
                            overlayServerList.remove(key);
                            Log.d(DEBUG_TAG, "Removed overlay tile layer " + key);
                        }
                    }
                }
            }
        }
    }

    /**
     * Set the imagery black list
     * 
     * @param bl a List of Strings containing regexps
     */
    public static void setBlacklist(@Nullable List<String> bl) {
        imageryBlacklist = bl;
    }

    /**
     * Getter for the start date
     * 
     * @return the start date as ms since the epoch
     */
    public long getStartDate() {
        return startDate;
    }

    /**
     * Getter for the end date
     * 
     * @return the end date as ms since the epoch
     */
    public long getEndDate() {
        return endDate;
    }

    /**
     * Get the Header used to indicate that a tile is not valid
     * 
     * @return a String with the header or null
     */
    @Nullable
    public String getNoTileHeader() {
        return noTileHeader;
    }

    /**
     * Get any values associated with the no tile header
     * 
     * @return a String array with the values or null
     */
    @Nullable
    public String[] getNoTileValues() {
        return noTileValues;
    }

    /**
     * Get any layer specific "no tile" tile
     * 
     * @return the tile or null if none set
     */
    @Nullable
    public byte[] getNoTileTile() {
        return noTileTile;
    }

    /**
     * Set the "not tile" tile
     * 
     * @param noTileTile the data corresponding to the tile
     */
    public void setNoTileTile(@Nullable byte[] noTileTile) {
        this.noTileTile = noTileTile;
    }

    /**
     * Return the attribution URL string of the 1st provider
     * 
     * Assumption is that in the simple case there is only one provider
     * 
     * @return a string containing the attribution URL or the ToU URL or null if neither exist
     */
    @Nullable
    public String getAttributionUrl() {
        String url = null;
        if (!getProviders().isEmpty()) {
            url = getProviders().get(0).getAttributionUrl();
        }
        if (url == null) { // still null
            url = getTouUri();
        }
        return url;
    }

    /**
     * Return the attribution string of the 1st provider
     * 
     * Assumption is that in the simple case there is only one provider
     * 
     * @return a string containing attribution information or null if none
     */
    @Nullable
    public String getAttribution() {
        if (!getProviders().isEmpty()) {
            return getProviders().get(0).getAttribution();
        }
        return null;
    }

    /**
     * Return the coverage areas of the 1st provider
     * 
     * Assumption is that in the simple case there is only one provider
     * 
     * @return a List of Provider.CoverageArea
     */
    @Nullable
    public List<Provider.CoverageArea> getCoverage() {
        if (!getProviders().isEmpty()) {
            return getProviders().get(0).getCoverageAreas();
        }
        return null;
    }

    /**
     * Check the overlay flag
     * 
     * @return true if this is an overlay
     */
    public boolean isOverlay() {
        return overlay;
    }

    /**
     * Set the overlay flag
     * 
     * @param overlay if true the layer will be considered for overlaying
     */
    void setOverlay(boolean overlay) {
        this.overlay = overlay;
    }

    /**
     * Get the default flag
     * 
     * @return true if the default layer flag is set
     */
    public boolean isDefaultLayer() {
        return defaultLayer;
    }

    /**
     * Return the projection that should be used if this is a WMS server
     * 
     * @return the projection as a String or null if not a WMS server
     */
    @Nullable
    public String getProj() {
        return proj;
    }

    /**
     * Get the preference/ranking for this layer
     * 
     * @return a numeric indication of preference, higher is better, 0 the default
     */
    public int getPreference() {
        return preference;
    }

    /**
     * Get the url for the logo
     * 
     * @return a string with the url or null if none exists
     */
    @Nullable
    public String getLogoUrl() {
        return logoUrl;
    }

    /**
     * Get the bitmap for the logo
     * 
     * @return a Bitmap or null if none exists
     */
    @Nullable
    public Bitmap getLogo() {
        return logoBitmap;
    }

    /**
     * Get the processed tile url
     * 
     * @return the processed (non-tile specific replacement made) tile url
     */
    @NonNull
    public String getTileUrl() {
        return tileUrl;
    }

    /**
     * ¨ Get the tile url
     * 
     * @return the tile url
     */
    @NonNull
    public String getOriginalTileUrl() {
        return originalUrl;
    }

    /**
     * Set the unprocessed url
     * 
     * @param originalUrl the unprocessed url
     */
    void setOriginalTileUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    @Override
    public String toString() {
        return "ID: " + id + " Name " + name + " maxZoom " + getMaxZoom() + " Tile URL " + tileUrl;
    }

    /**
     * Get the type "tms", "wms", "bing", "scanex" of the layer
     * 
     * @return the layer type
     */
    public String getType() {
        return type;
    }

    /**
     * @return the tileType
     */
    public TileType getTileType() {
        return tileType;
    }

    /**
     * Set the TileType for this source
     * 
     * @param tileType the TileType for this source
     */
    public void setTileType(@NonNull TileType tileType) {
        this.tileType = tileType;
    }

    /**
     * Get the category for the layer
     * 
     * @return the category or null if not set
     */
    @Nullable
    public Category getCategory() {
        return category;
    }

    /**
     * Set the category for this layer
     * 
     * @param cat the Category or null
     */
    public void setCategory(@Nullable Category cat) {
        this.category = cat;
    }

    /**
     * Set provider list to a single Provider
     * 
     * @param provider Provider to use
     */
    public void setProvider(@Nullable Provider provider) {
        getProviders().clear();
        if (provider != null) {
            getProviders().add(provider);
        }
    }

    /**
     * Get a BoundingBox that covers all of the layers CoverageAreas
     * 
     * @return a BoundingBox covering all CoverageAreas
     */
    @NonNull
    public BoundingBox getOverallCoverage() {
        if (getProviders().isEmpty() || getProviders().get(0).getCoverageAreas() == null || getProviders().get(0).getCoverageAreas().isEmpty()) {
            return ViewBox.getMaxMercatorExtent();
        }
        BoundingBox box = null;
        for (Provider provider : getProviders()) {
            for (CoverageArea coverage : provider.getCoverageAreas()) {
                if (box == null) {
                    box = new BoundingBox(coverage.bbox);
                } else {
                    box.union(coverage.bbox);
                }
            }
        }
        return box; // NOSONAR
    }

    /**
     * @return the zoomLevelMin
     */
    public int getMinZoom() {
        return zoomLevelMin;
    }

    /**
     * @param zoomLevelMin the zoomLevelMin to set
     */
    public void setMinZoom(int zoomLevelMin) {
        this.zoomLevelMin = zoomLevelMin;
    }

    /**
     * Get the maximum zoom Level
     * 
     * @return the maximum zoom Level
     */
    public synchronized int getMaxZoom() {
        return zoomLevelMax;
    }

    /**
     * Set the maximum zoom
     * 
     * If the offsets array already has been allocated this will expand it if necessary (currently only possible for
     * bing)
     * 
     * @param zoomLevelMax the zoomLevelMax to set
     */
    public synchronized void setMaxZoom(int zoomLevelMax) {
        if (offsets != null && offsets.length < zoomLevelMax) {
            Offset[] tempOffsets = new Offset[zoomLevelMax - this.zoomLevelMin + 1];
            System.arraycopy(offsets, 0, tempOffsets, 0, offsets.length);
            offsets = tempOffsets;
        }
        this.zoomLevelMax = zoomLevelMax;
    }

    /**
     * @return the description
     */
    @Nullable
    public String getDescription() {
        return description;
    }

    /**
     * @return the privacyPolicyUrl
     */
    @Nullable
    public String getPrivacyPolicyUrl() {
        return privacyPolicyUrl;
    }

    /**
     * Add or update a custom tile source
     * 
     * @param ctx an Android Context
     * @param db a writable database
     * @param layerId if for the layer
     * @param existingTileServer if this is an update, this is the existing server
     * @param startDate start date or -1
     * @param endDate end date or -1
     * @param name name of the layer
     * @param provider Provider object
     * @param category layer Category
     * @param type type of the entry
     * @param tileType the type of tile if null the automatic detection will be used
     * @param minZoom minimum zoom level
     * @param maxZoom maximum zoom level
     * @param tileSize tile size to use
     * @param isOverlay if true add as an overlay
     * @param tileUrl the url for the tiles
     */
    public static void addOrUpdateCustomLayer(@NonNull final Context ctx, @NonNull final SQLiteDatabase db, @NonNull final String layerId,
            @Nullable final TileLayerSource existingTileServer, final long startDate, final long endDate, @NonNull String name, @Nullable Provider provider,
            Category category, @Nullable String type, @Nullable TileType tileType, int minZoom, int maxZoom, int tileSize, boolean isOverlay,
            @NonNull String tileUrl) {
        String proj = null;
        // hack, but saves people extracting and then having to re-select the projection
        if (tileUrl.contains(EPSG_3857) || tileUrl.contains(EPSG_900913)) {
            proj = EPSG_3857;
            tileSize = WMS_TILE_SIZE;
        } else if (tileUrl.contains(EPSG_4326)) {
            proj = EPSG_4326;
        }
        if (type == null) {
            type = proj == null ? TYPE_TMS : TYPE_WMS; // heuristic
        }
        if (existingTileServer == null) {
            TileLayerSource layer = new TileLayerSource(ctx, layerId, name, tileUrl, type, category, isOverlay, false, provider, null, null, null, null,
                    minZoom, maxZoom, TileLayerSource.DEFAULT_MAX_OVERZOOM, tileSize, tileSize, proj, 0, startDate, endDate, null, null, null, null, true);
            if (tileType != null) { // if null use automatic detection
                layer.setTileType(tileType);
            }
            TileLayerDatabase.addLayer(db, TileLayerDatabase.SOURCE_MANUAL, layer);
        } else {
            existingTileServer.setProvider(provider);
            existingTileServer.setName(name);
            existingTileServer.setOriginalTileUrl(tileUrl);
            existingTileServer.setOverlay(isOverlay);
            existingTileServer.setMinZoom(minZoom);
            existingTileServer.setMaxZoom(maxZoom);
            existingTileServer.setCategory(category);
            existingTileServer.setProj(proj);
            existingTileServer.setType(type);
            if (tileType != null) { // if null use automatic detection
                existingTileServer.setTileType(tileType);
            }
            existingTileServer.setTileWidth(tileSize);
            existingTileServer.setTileHeight(tileSize);
            TileLayerDatabase.updateLayer(db, existingTileServer);
        }
    }

    private static final Pattern APIKEY_PATTERN = Pattern.compile(".*\\{apikey\\}.*", Pattern.CASE_INSENSITIVE);

    /**
     * Replace any apikey placeholder if possible
     * 
     * @param context Android Context
     * @return true if the apikey could be found or wasn't required, false otherwise
     */
    public boolean replaceApiKey(@NonNull Context context) {
        if (APIKEY_PATTERN.matcher(tileUrl).matches()) {
            // check key database
            KeyDatabaseHelper keys = new KeyDatabaseHelper(context);
            try (SQLiteDatabase db = keys.getReadableDatabase()) {
                String key = KeyDatabaseHelper.getKey(db, getId(), EntryType.IMAGERY);
                if (key != null && !"".equals(key)) {
                    setTileUrl(replaceParameter(tileUrl, "apikey", key));
                    return true;
                }
            } finally {
                keys.close();
            }
            return false; // needed key but didn't find it
        }
        return true;
    }

    /**
     * Return the source of the entry
     * 
     * That is eli, josm, custom, manual see TileLayerDatabase for constants
     * 
     * @return the source
     */
    public String getSource() {
        return source;
    }

    /**
     * Set the source of the entry
     * 
     * @param source the source to set
     */
    public void setSource(@NonNull String source) {
        this.source = source;
    }

    /**
     * Set the projection used by the layer
     * 
     * @param proj the proj to set
     */
    private void setProj(@Nullable String proj) {
        this.proj = proj;
    }

    /**
     * Set the type of the entry (mainly tms, wms, wms_endpoint and some special cases)
     * 
     * @param type the type to set
     */
    private void setType(@NonNull String type) {
        this.type = type;
    }

    /**
     * @param logoDrawable the logoDrawable to set
     */
    public void setLogoDrawable(Drawable logoDrawable) {
        this.logoDrawable = logoDrawable;
    }

    /**
     * @param tileUrl the tileUrl to set
     */
    public void setTileUrl(String tileUrl) {
        this.tileUrl = tileUrl;
    }

    /**
     * @param imageFilenameExtension the imageFilenameExtension to set
     */
    public void setImageExtension(@Nullable String imageFilenameExtension) {
        this.imageFilenameExtension = imageFilenameExtension;
    }

    /**
     * Get the filename extensions that applies to the tile images.
     * 
     * @return Image filename extension, eg "png" without "." .
     */
    @Nullable
    public String getImageExtension() {
        return imageFilenameExtension;
    }

    /**
     * @return the subdomains
     */
    @Nullable
    public Queue<String> getSubdomains() {
        return subdomains;
    }

    /**
     * @param tileWidth the tileWidth to set
     */
    public void setTileWidth(int tileWidth) {
        this.tileWidth = tileWidth;
    }

    /**
     * @param tileHeight the tileHeight to set
     */
    public void setTileHeight(int tileHeight) {
        this.tileHeight = tileHeight;
    }

    /**
     * @return the providers
     */
    public List<Provider> getProviders() {
        return providers;
    }
}
