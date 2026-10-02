package com.dogpound.prideperms;
public class ChatStyleCheck {
    static void eq(String want, String got, String what) { if (!want.equals(got)) throw new AssertionError(what + ": wanted [" + want + "] got [" + got + "]"); System.out.println("ok  " + what + " → " + got); }
    public static void main(String[] a) {
        eq("I §cred", ChatStyle.style("I &cred", true, false, true), "colour allowed");
        eq("I &cred", ChatStyle.style("I &cred", false, false, true), "colour not allowed stays plain");
        eq("§lbold §cred", ChatStyle.style("&lbold &cred", true, true, true), "format allowed");
        eq("&lbold §cred", ChatStyle.style("&lbold &cred", true, false, true), "format NOT allowed stays plain");
        eq("love ❤ you ★", ChatStyle.style("love :heart: you :star:", false, false, true), "emoji shortcodes");
        eq("love :heart:", ChatStyle.style("love :heart:", false, false, false), "emoji off");
        eq("time 10:30 :nope:", ChatStyle.style("time 10:30 :nope:", true, true, true), "clock times and unknown codes untouched");
        eq("a & b", ChatStyle.style("a & b", true, true, true), "lone & untouched");
        System.out.println("ALL PASS");
    }
}
