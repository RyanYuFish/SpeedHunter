import com.github.speedhunter.ota.MirrorRequest;

public class MirrorRequestTest {
    private static int count;
    static void rejects(String qr) {
        try { MirrorRequest.parse(qr); throw new AssertionError("accepted malformed QR"); }
        catch (IllegalArgumentException expected) { count++; }
    }
    public static void main(String[] args) {
        String raw = "https://backend.example.com/app_download/?action=9&name=SPEED%2BA%20B&pwd=a%26b%3Dc&mac=12%3A34&ssid=AP";
        MirrorRequest qr = MirrorRequest.parse(raw);
        if (!raw.equals(qr.url) || !"SPEED+A B".equals(qr.name)) throw new AssertionError("QR payload changed");
        count++;
        if (!"CAR".equals(MirrorRequest.parse("https://x.test/?action=8&name=CAR").name)) throw new AssertionError(); count++;
        MirrorRequest.parse("https://x.test/?action=wifi&name=CAR"); count++;
        rejects(null); rejects(""); rejects("DIRECT-1234"); rejects("file:///tmp/?action=9&name=CAR");
        rejects("https://x.test/?action=9&ssid=AP"); rejects("https://x.test/?action=9&name=");
        rejects("https://x.test/?action=1&name=CAR"); rejects("https://x.test/?action=2&name=CAR");
        rejects("https://x.test/?action=no&name=CAR"); rejects("https://x.test/?action=-1&name=CAR");
        rejects("https://x.test/?action=9&name=CAR&name=OTHER");
        rejects("https://x.test/?action=9&name=%GG"); rejects("https://x.test/?action=9&name="+"X".repeat(129));
        rejects("https://x.test/?action=9&name=CAR&pwd="+"X".repeat(8192));
        System.out.println("PASS: "+count+" QR compatibility/validation cases");
    }
}
