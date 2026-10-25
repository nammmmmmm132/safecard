package com.example.safecard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Tiện ích dựng giao diện bằng code cho các màn hình phụ. */
public class UI {
    public static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density); }

    public static LinearLayout page(Activity a) {
        ScrollView sv = new ScrollView(a);
        LinearLayout ll = new LinearLayout(a);
        ll.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 16);
        ll.setPadding(p, p, p, p);
        sv.addView(ll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        a.setContentView(sv);
        return ll;
    }

    public static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(24);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    public static TextView text(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(c, 6), 0, dp(c, 6));
        return t;
    }

    public static Button btn(Context c, String s, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    public static EditText edit(Context c, String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(inputType);
        return e;
    }

    public static CheckBox check(Context c, String s) {
        CheckBox cb = new CheckBox(c);
        cb.setText(s);
        return cb;
    }
}
