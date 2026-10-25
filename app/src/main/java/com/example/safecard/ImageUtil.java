package com.example.safecard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayOutputStream;
import java.io.File;

public class ImageUtil {
    public static File file(Context c, String name) {
        File d = new File(c.getCacheDir(), "images");
        d.mkdirs();
        return new File(d, name);
    }

    public static Uri uri(Context c, File f) {
        return FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
    }

    /** Đọc ảnh, xoay đúng chiều, thu nhỏ cạnh dài về tối đa max px. */
    public static Bitmap load(File f, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > max * 2) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (b == null) return null;
            int rot = 0;
            int ori = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            float scale = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
            Matrix m = new Matrix();
            m.postRotate(rot);
            m.postScale(scale, scale);
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Exception e) {
            return null;
        }
    }

    public static String toBase64(File f) {
        Bitmap b = load(f, 800);
        if (b == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 60, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    public static Bitmap fromBase64(String s) {
        if (s == null) return null;
        byte[] d = Base64.decode(s, Base64.NO_WRAP);
        return BitmapFactory.decodeByteArray(d, 0, d.length);
    }
}
