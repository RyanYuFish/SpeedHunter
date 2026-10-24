import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.github.speedhunter.ota.MirrorRequest;
import java.util.EnumMap;
import java.util.Map;

/** Actual QR codec -> production URL parser. Does not simulate a phone camera. */
public final class MirrorQrDecodeTest {
    static String scan(String value) throws Exception {
        Map<EncodeHintType,Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600, hints);
        int[] pixels = new int[600*600];
        for (int y=0;y<600;y++) for (int x=0;x<600;x++) pixels[y*600+x]=matrix.get(x,y)?0xff000000:0xffffffff;
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(600,600,pixels)))).getText();
    }
    public static void main(String[] args) throws Exception {
        String[] urls = {
            "https://backend.example.com/app_download/?action=9&name=SPEED-TEST&ssid=AP&pwd=a%26b%3Dc%2B&mac=12%3A34%3A56",
            "https://x.test/?action=8&name=%E6%B5%8B%E8%AF%95%E8%BD%A6%E5%9E%8B&ssid=SPEED%2BTEST",
            "https://x.test/?action=wifi&name=CAR",
            "https://x.test/?name=CAR&action=9&pwd="
        };
        int count=0;
        for (String original:urls) {
            String decoded=scan(original);
            MirrorRequest qr=MirrorRequest.parse(decoded);
            if(!original.equals(qr.url)) throw new AssertionError("raw QR URL changed");
            count++;
        }
        MirrorRequest info=MirrorRequest.parse(scan(urls[0]));
        if(!"SPEED-TEST".equals(info.name)||!"AP".equals(info.ssid)||!"12:34:56".equals(info.mac)||!info.hasPassword) throw new AssertionError("connection summary wrong"); count++;
        info=MirrorRequest.parse(scan(urls[1]));
        if(!"测试车型".equals(info.name)||!"SPEED+TEST".equals(info.ssid)||info.hasPassword) throw new AssertionError("Unicode/plus metadata wrong"); count++;
        for(String invalid:new String[]{"ordinary text","https://x.test/?action=9&ssid=AP","https://x.test/?action=1&name=CAR"}) {
            String decoded=scan(invalid);
            try {MirrorRequest.parse(decoded);throw new AssertionError("accepted wrong QR");}
            catch(IllegalArgumentException expected) {count++;}
        }
        System.out.println("PASS: "+count+" real QR decoding and connection-info cases (no camera hardware test)");
    }
}
