/*
 * Copyright (C) 2010-12  Ciaran Gultnieks, ciaran@ciarang.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.fdroid.fdroid;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import androidx.collection.LongSparseArray;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.nostra13.universalimageloader.cache.disc.DiskCache;
import com.nostra13.universalimageloader.cache.disc.impl.UnlimitedDiskCache;
import com.nostra13.universalimageloader.cache.disc.impl.ext.LruDiskCache;
import com.nostra13.universalimageloader.core.DefaultConfigurationFactory;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.nostra13.universalimageloader.core.process.BitmapProcessor;

import org.fdroid.fdroid.Preferences.Theme;
import org.fdroid.fdroid.data.AppProvider;
import org.fdroid.fdroid.data.InstalledAppProviderService;
import org.fdroid.fdroid.data.Repo;
import org.fdroid.fdroid.data.RepoProvider;
import org.fdroid.fdroid.net.ImageLoaderForUIL;
import org.ligi.tracedroid.TraceDroid;

import java.io.IOException;
import java.util.Locale;

import javax.microedition.khronos.opengles.GL10;

import info.guardianproject.netcipher.NetCipher;
import info.guardianproject.netcipher.proxy.OrbotHelper;

public class FDroidApp extends Application {

    private static final String TAG = "FDroidApp";

    public static final String SYSTEM_DIR_NAME = Environment.getRootDirectory().getAbsolutePath();

    private static Locale locale;

    private static Theme curTheme = Theme.light;

    public void reloadTheme() {
        curTheme = Preferences.get().getTheme();
    }

    public void applyTheme(Activity activity) {
        activity.setTheme(getCurThemeResId());
    }
    public static Context getInstance() {
        return instance;
    }
    private static FDroidApp instance;

    public void applyDialogTheme(Activity activity) {
        activity.setTheme(getCurDialogThemeResId());
    }

    public static int getCurThemeResId() {
        switch (curTheme) {
            case light:
                return R.style.AppThemeLight;
            case dark:
                return R.style.AppThemeDark;
            case night:
                return R.style.AppThemeNight;
            default:
                return R.style.AppThemeLight;
        }
    }

    private static int getCurDialogThemeResId() {
        switch (curTheme) {
            case light:
                return R.style.MinWithDialogBaseThemeLight;
            case dark:
                return R.style.MinWithDialogBaseThemeDark;
            case night:
                return R.style.MinWithDialogBaseThemeNight;
            default:
                return R.style.MinWithDialogBaseThemeLight;
        }
    }

    /**
     * Force reload the {@link Activity to make theme changes take effect.}
\     *
     * @param activity the {@code Activity} to force reload
     */
    public static void forceChangeTheme(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null) { // when launched as LAUNCHER
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.finish();
        activity.overridePendingTransition(0, 0);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    public void updateLanguage() {
        Context ctx = getBaseContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String lang = prefs.getString(Preferences.PREF_LANGUAGE, "");
        locale = Utils.getLocaleFromAndroidLangTag(lang);
        applyLanguage();
    }

    private void applyLanguage() {
        Context ctx = getBaseContext();
        Configuration cfg = new Configuration();
        cfg.locale = locale == null ? Locale.getDefault() : locale;
        ctx.getResources().updateConfiguration(cfg, null);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLanguage();
    }
    public static int getTimeout() {
        return timeout;
    }
    public static String getMirror(String urlString, long repoId) throws IOException {
        return getMirror(urlString, RepoProvider.Helper.findById(getInstance(), repoId));
    }

    public static String getMirror(String urlString, Repo repo2) throws IOException {
        if (repo2.hasMirrors()) {
            String lastWorkingMirror = lastWorkingMirrorArray.get(repo2.getId());
            if (lastWorkingMirror == null) {
                lastWorkingMirror = repo2.address;
            }
            if (numTries <= 0) {
                if (timeout == 10000) {
                    timeout = 30000;
                    numTries = Integer.MAX_VALUE;
                } else if (timeout == 30000) {
                    timeout = 60000;
                    numTries = Integer.MAX_VALUE;
                } else {
                    Utils.debugLog(TAG, "Mirrors: Giving up");
                    throw new IOException("Ran out of mirrors");
                }
            }
            if (numTries == Integer.MAX_VALUE) {
                numTries = repo2.getMirrorCount();
            }
            String mirror = repo2.getMirror(lastWorkingMirror);
            String newUrl = urlString.replace(lastWorkingMirror, mirror);
            Utils.debugLog(TAG, "Trying mirror " + mirror + " after " + lastWorkingMirror + " failed," +
                    " timeout=" + timeout / 1000 + "s");
            lastWorkingMirrorArray.put(repo2.getId(), mirror);
            numTries--;
            return newUrl;
        } else {
            throw new IOException("No mirrors available");
        }
    }
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        TraceDroid.init(this);
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
        }
        updateLanguage();

        Preferences.setup(this);
        curTheme = Preferences.get().getTheme();
        Preferences.get().configureProxy();

        InstalledAppProviderService.compareToPackageManager(this);

        // If the user changes the preference to do with filtering rooted apps,
        // it is easier to just notify a change in the app provider,
        // so that the newly updated list will correctly filter relevant apps.
        Preferences.get().registerAppsRequiringRootChangeListener(new Preferences.ChangeListener() {
            @Override
            public void onPreferenceChange() {
                getContentResolver().notifyChange(AppProvider.getContentUri(), null);
            }
        });

        // If the user changes the preference to do with filtering anti-feature apps,
        // it is easier to just notify a change in the app provider,
        // so that the newly updated list will correctly filter relevant apps.
        Preferences.get().registerAppsRequiringAntiFeaturesChangeListener(new Preferences.ChangeListener() {
            @Override
            public void onPreferenceChange() {
                getContentResolver().notifyChange(AppProvider.getContentUri(), null);
            }
        });

        final Context context = this;
        Preferences.get().registerUnstableUpdatesChangeListener(new Preferences.ChangeListener() {
            @Override
            public void onPreferenceChange() {
                AppProvider.Helper.calcSuggestedApks(context);
            }
        });

        CleanCacheService.schedule(this);

        UpdateService.schedule(getApplicationContext());

        // There are a couple things to pay attention to with this config: memory usage,
        // especially on small devices; and, image processing vulns, since images are
        // submitted via app's git repos, so anyone with commit privs there could submit
        // exploits hidden in images.  Luckily, F-Droid doesn't need EXIF at all, and
        // that is where the JPEG/PNG vulns have been. So it can be entirely stripped.
        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        int maxSize = GL10.GL_MAX_TEXTURE_SIZE; // see ImageScaleType.NONE_SAFE javadoc
        int width = display.getWidth();
        if (width > maxSize) {
            maxSize = width;
        }
        int height = display.getHeight();
        if (height > maxSize) {
            maxSize = height;
        }
        DiskCache diskCache;
        long available = Utils.getImageCacheDirAvailableMemory(this);
        int percentageFree = Utils.getPercent(available, Utils.getImageCacheDirTotalMemory(this));
        if (percentageFree > 5) {
            diskCache = new UnlimitedDiskCache(Utils.getImageCacheDir(this));
        } else {
            Log.i(TAG, "Switching to LruDiskCache(" + available / 2L + ") to save disk space!");
            try {
                diskCache = new LruDiskCache(Utils.getImageCacheDir(this),
                        DefaultConfigurationFactory.createFileNameGenerator(),
                        available / 2L);
            } catch (IOException e) {
                diskCache = new UnlimitedDiskCache(Utils.getImageCacheDir(this));
            }
        }
        ImageLoaderConfiguration config = new ImageLoaderConfiguration.Builder(getApplicationContext())
                .imageDownloader(new ImageLoaderForUIL(getApplicationContext()))
                .defaultDisplayImageOptions(Utils.getDefaultDisplayImageOptionsBuilder().build())
                .diskCache(diskCache)
                .diskCacheExtraOptions(maxSize, maxSize, new BitmapProcessor() {
                    @Override
                    public Bitmap process(Bitmap bitmap) {
                        // converting JPEGs to Bitmaps, then saving them removes EXIF metadata
                        return bitmap;
                    }
                })
                .threadPoolSize(getThreadPoolSize())
                .build();
        ImageLoader.getInstance().init(config);

        configureTor(Preferences.get().isTorEnabled());
    }

    /**
     * Return the number of threads Universal Image Loader should use, based on
     * the total RAM in the device.  Devices with lots of RAM can do lots of
     * parallel operations for fast icon loading.
     */
    @TargetApi(16)
    private int getThreadPoolSize() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memInfo);
            return (int) Math.max(1, Math.min(16, memInfo.totalMem / 256 / 1024 / 1024));
        }
        return 2;
    }

    private static volatile LongSparseArray<String> lastWorkingMirrorArray = new LongSparseArray<>(1);
    private static volatile int numTries = Integer.MAX_VALUE;
    private static volatile int timeout = 10000;
    public static void resetMirrorVars() {
        // Reset last working mirror, numtries, and timeout
        for (int i = 0; i < lastWorkingMirrorArray.size(); i++) {
            lastWorkingMirrorArray.removeAt(i);
        }
        numTries = Integer.MAX_VALUE;
        timeout = 10000;
    }

    private static boolean useTor;

    /**
     * Set the proxy settings based on whether Tor should be enabled or not.
     */
    private static void configureTor(boolean enabled) {
        useTor = enabled;
        if (useTor) {
            NetCipher.useTor();
        } else {
            NetCipher.clearProxy();
        }
    }

    public static void checkStartTor(Context context) {
        if (useTor) {
            OrbotHelper.requestStartTor(context);
        }
    }

    public static boolean isUsingTor() {
        return useTor;
    }
}
