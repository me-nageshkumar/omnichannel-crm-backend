package me.nageshk.omnichannel.service.page;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Action;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.ActionButton;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Body;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive.Reply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Spec §5.4: the link-preview card is a 1200×630 PNG drawn without a browser. */
class PreviewCardRendererTest {

    private final PreviewCardRenderer r = new PreviewCardRenderer();
    private final CloudMessages cloud = new CloudMessages(JsonMapper.builder().build());

    CloudMessage fixture(String name) throws Exception {
        return cloud.parse(JsonMapper.builder().build().readTree(getClass().getResourceAsStream("/cloud/" + name + ".json")));
    }

    static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    static byte[] solidPng(Color c) throws Exception {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 10, 10);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(strings = {"button", "list", "cta_url", "location_request"})
    void drawsA1200x630PngForEachKind(String name) throws Exception {
        BufferedImage img = decode(r.draw(fixture(name), Optional.empty()));
        assertThat(img.getWidth()).isEqualTo(1200);
        assertThat(img.getHeight()).isEqualTo(630);
    }

    @Test
    void usesTheHeaderImageWhenGiven() throws Exception {
        BufferedImage img = decode(r.draw(fixture("button"), Optional.of(solidPng(Color.RED))));
        Color left = new Color(img.getRGB(100, 315));
        assertThat(left.getRed()).isGreaterThan(200);
        assertThat(left.getGreen()).isLessThan(80);
    }

    @Test
    void greenPanelWithoutHeaderImage() throws Exception {
        BufferedImage img = decode(r.draw(fixture("button"), Optional.empty()));
        Color left = new Color(img.getRGB(100, 100));
        assertThat(left).isEqualTo(new Color(0x07, 0x5e, 0x54));
        Color right = new Color(img.getRGB(1150, 20));
        assertThat(right).isEqualTo(Color.WHITE);
    }

    @Test
    void corruptHeaderImageFallsBackToPanel() throws Exception {
        BufferedImage img = decode(r.draw(fixture("button"), Optional.of(new byte[] {1, 2, 3})));
        assertThat(new Color(img.getRGB(100, 100))).isEqualTo(new Color(0x07, 0x5e, 0x54));
    }

    @Test
    void veryLongBodyStillFits() throws Exception {
        CloudMessage m = new CloudMessage("whatsapp-web", null, "1", "interactive", null,
                new Interactive("button", null, new Body("a".repeat(1024)), null,
                        new Action(List.of(new ActionButton("reply", new Reply("a", "A"))), null, null, null, null)), null);
        assertThat(decode(r.draw(m, Optional.empty())).getWidth()).isEqualTo(1200);
        CloudMessage words = new CloudMessage("whatsapp-web", null, "1", "interactive", null,
                new Interactive("button", null, new Body("word ".repeat(300)), null,
                        new Action(List.of(new ActionButton("reply", new Reply("a", "A"))), null, null, null, null)), null);
        assertThat(decode(r.draw(words, Optional.empty())).getHeight()).isEqualTo(630);
    }

    @Test
    void drawsTextWithTheBundledFont() {
        assertThat(r.fontName()).startsWith("DejaVu");
    }
}
