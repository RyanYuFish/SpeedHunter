import com.github.zeehospeedhunter.mirror.AvcFrames;
import java.util.Arrays;
public final class AvcFramesTest {
 public static void main(String[] args) {
  byte[] annex={0,0,0,1,0x65,1};
  if(!Arrays.equals(annex,AvcFrames.annexB(annex)))throw new AssertionError();
  if(!Arrays.equals(annex,AvcFrames.annexB(new byte[]{0,0,0,2,0x65,1})))throw new AssertionError();
  if(!Arrays.equals(new byte[]{0,0,0,1,0x67,0,0,0,1,0x68},AvcFrames.annexB(new byte[]{0,0,0,1,0x67,0,0,0,1,0x68})))throw new AssertionError();
  for(byte[] b:new byte[][]{{},{0},{0,0,0,0},{0,0,0,9,0x65},{-1,-1,-1,-1},{0,0,0,2,0x65,1,0}}) {
   try { AvcFrames.annexB(b); throw new AssertionError("accepted malformed AVC"); } catch(IllegalArgumentException expected) {}
  }
  if(!AvcFrames.hasIdr(new byte[]{0,0,1,0x65,1}))throw new AssertionError("3-byte IDR");
  if(!AvcFrames.hasIdr(annex))throw new AssertionError("4-byte IDR");
  if(AvcFrames.hasIdr(new byte[]{0,0,0,1,0x67,1,0,0,0,1,0x41,1}))throw new AssertionError("P frame accepted");
  if(AvcFrames.hasIdr(new byte[]{0,0,1}))throw new AssertionError("missing NAL accepted");
  if(!AvcFrames.hasIdr(AvcFrames.annexB(new byte[]{0,0,0,2,0x65,1})))throw new AssertionError("AVCC IDR");
  System.out.println("AvcFrames: 14 cases passed");
 }
}
