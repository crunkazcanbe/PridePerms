package com.dogpound.prideperms;

/** server side: no screens */
public class CommonProxy {
    public void rulesArrived(String json, boolean open) {}
    public void cmdsArrived(String json) {}
    public void init() {}
    public void gateArrived(Net.GateScreen m) {}
    public void replyArrived(String text) {}
}
