package loader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by 25400 on 2019/12/2.
 */

public class ImageLoader {
    private static final String TAG = "ImageLoader";

    public static final int MESSAGE_POST_RESULT = 1;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;
    private static final int TAG_KEY_URI = R.id.imageloader_uri;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mIsDiskLruCacheCreated = false;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), sThreadFactory);

    private Handler mMainHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            String uri = (String) imageView.getTag(TAG_KEY_URI);
            if (uri.equals(result.uri)){
                imageView.setImageBitmap(result.bitmap);
            }else {
                Log.w(TAG, "set image bitmap,but url has changed, ignored!");
            }
        };
    };

    private Context mContext;
    private ImageResizer mImageResizer = new ImageResizer();
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;

    public ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
            }
        };
        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()){
            diskCacheDir.mkdir();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            }catch (IOException e){
                e.printStackTrace();
            }
        }
    }

    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    private void addBitmapToMemoryCache(String key, Bitmap bitmap){
        if (getBitmapFromMemCache(key) == null){
            mMemoryCache.put(key, bitmap);
        }
    }

    private Bitmap getBitmapFromMemCache(String key){
        return mMemoryCache.get(key);
    }

    public void bindBitmap(final String uri, final ImageView imageView){
        imageView.setTag(TAG_KEY_URI, uri);
        Bitmap bitmap = loadBitmapFromMenCache(uri);
        if (bitmap != null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri, reqWidth, reqHeight);
                if (bitmap != null){
                    LoaderResult result = new LoaderResult(imageView, uri, bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight){
        Bitmap bitmap = loadBitmapFromMenCache(uri);
        if (bitmap != null) {
            Log.d(TAG, "loadBitmapFromMemCache,url:" + uri);
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null) {
                Log.d(TAG, "loadBitmapFromDisk,url:" + uri);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
            Log.d(TAG, "loadBitmapFromHttp,url:" + uri);
        } catch (IOException e){
            e.printStackTrace();
        }
        
        if (bitmap == null && !mIsDiskLruCacheCreated){
            Log.w(TAG, "encounter error, DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(uri);
        }
        return bitmap;
    }

    private Bitmap loadBitmapFromMenCache(String uri){
        final String key = hashKeyFromUrl(uri);
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;
    }

    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException{
        if (Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network from UI Thread.");
        }
        if (mDiskLruCache == null){
            return  null;
        }

        String key = hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "load bitmap from UI Thread, it's not recommended!");
        }
        if (mDiskLruCache == null) {
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapShot = mDiskLruCache.get(key);
        if (snapShot != null) {
           FileInputStream fileInputStream = (FileInputStream)snapShot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampledBitmapFromFileDescriptor(fileDescriptor,
                    reqWidth, reqHeight);
            if (bitmap != null) {
                addBitmapToMemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }
    
    public boolean downloadUrlToStream(String urlString, OutputStream outputStream){
        HttpURLConnection urlConntection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            urlConntection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConntection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int b;
            while ((b = in.read()) != -1){
                out.write(b);
            }
            return true;
        }catch (IOException e){
            Log.e(TAG, "downloadBitmap failed." + e);
        }finally {
            if (urlConntection != null){
                urlConntection.disconnect();
            }
            MyUtils.close(out);
            MyUtils.close(in);
        }
        return false;
    }

    private Bitmap downloadBitmapFromUrl(String urlString){
        Bitmap bitmap = null;
        HttpURLConnection urlConntection = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(urlString);
            urlConntection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConntection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        }catch (final IOException e){
            Log.e(TAG, "Error in downloadBitmap: " + e);
        }finally {
            if (urlConntection != null) {
                urlConntection.disconnect();
            }
            MyUtils.close(in);
        }
        return bitmap;
    }

    private String hashKeyFromUrl(String url){
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey =  bytesToHexString(mDigest.digest());
        }catch (NoSuchAlgorithmException e){
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++){
            String hex = Integer.toHexString(0xFF * bytes[i]);
            if (hex.length() == 1){
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    public File getDiskCacheDir(Context context, String uniqueName){
        
    }
}
