package com.github.zeehospeedhunter.mirror;
import java.io.ByteArrayOutputStream;
/** Normalizes MediaCodec's Annex-B or length-prefixed AVC buffers. */
public final class AvcFrames {
    public static boolean hasIdr(byte[] a) {
        for(int i=0;i+3<a.length;i++) {
            if(a[i]!=0 || a[i+1]!=0) continue;
            int nal=-1;
            if(a[i+2]==1) nal=i+3;
            else if(i+4<a.length && a[i+2]==0 && a[i+3]==1) nal=i+4;
            if(nal>=0 && (a[nal]&31)==5) return true;
        }
        return false;
    }
    public static byte[] annexB(byte[] a) {
        if(a.length>=4 && a[0]==0 && a[1]==0 && (a[2]==1 || (a[2]==0 && a[3]==1))) return a;
        ByteArrayOutputStream out=new ByteArrayOutputStream(); int p=0;
        while(p<a.length) {
            if(a.length-p<4) throw new IllegalArgumentException("AVC 长度头不完整");
            long n=((a[p]&255L)<<24)|((a[p+1]&255L)<<16)|((a[p+2]&255L)<<8)|(a[p+3]&255L); p+=4;
            if(n<=0 || n>a.length-p) throw new IllegalArgumentException("AVC 数据长度无效");
            out.write(0); out.write(0); out.write(0); out.write(1); out.write(a,p,(int)n); p+=(int)n;
        }
        if(p==0) throw new IllegalArgumentException("AVC 帧为空");
        return out.toByteArray();
    }
}
