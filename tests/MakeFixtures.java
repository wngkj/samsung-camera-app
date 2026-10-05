import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
public class MakeFixtures {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]); directory.mkdirs();
        for (int i = 1; i <= 3; i++) {
            BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setPaint(new GradientPaint(0, 0, new Color(0, 100 + i * 20, 100), 800, 600, new Color(150, 210, 100 + i * 20)));
            g.fillRect(0, 0, 800, 600); g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 64));
            g.drawString("DEMO " + i, 80, 280); g.setFont(new Font("SansSerif", Font.PLAIN, 24)); g.drawString("WB800F protocol simulator", 80, 350); g.dispose();
            ImageIO.write(image, "jpg", new File(directory, "DEMO_" + i + ".jpg"));
            BufferedImage thumbnail = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
            Graphics2D tg = thumbnail.createGraphics(); tg.drawImage(image, 0, 0, 160, 120, null); tg.dispose();
            ImageIO.write(thumbnail, "jpg", new File(directory, "THUMB_" + i + ".jpg"));
        }
    }
}
