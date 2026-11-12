package me.nageshk.omnichannel.service.page;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.service.cloud.CloudMessageValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Draws the link-preview card of a message page: a 1200×630 PNG with the header image (or a WhatsApp-green panel) on
 * the left and the title, body and action labels on the right. Java2D only, so the server needs no browser; the font
 * travels in the jar so the server's font set does not matter (spec §5.4).
 */
@Component
public class PreviewCardRenderer {

    private static final Logger log = LoggerFactory.getLogger(PreviewCardRenderer.class);

    static final int WIDTH = 1200;
    static final int HEIGHT = 630;
    static final int PANEL = 480;
    private static final int PAD = 48;
    private static final Color GREEN = new Color(0x07, 0x5e, 0x54);
    private static final Color INK = new Color(0x11, 0x1b, 0x21);
    private static final Color MUTED = new Color(0x54, 0x65, 0x6f);
    private static final Color BLUE = new Color(0x00, 0xa5, 0xf4);
    private static final int MAX_TITLE_LINES = 2;
    private static final int MAX_BODY_LINES = 6;

    private final Font base;

    public PreviewCardRenderer() {
        this.base = loadFont();
    }

    /** The family of the font in use, for tests and logs. */
    public String fontName() {
        return base.getFamily();
    }

    /** The bundled font, for tests. */
    public Font baseFont() {
        return base;
    }

    /** A stretch of text and the font that can show it. */
    public record Run(String text, Font font) {}

    /**
     * Splits text into runs the given font can display and runs it cannot (emoji, Indic and other scripts the
     * bundled font lacks), which go to the JVM's logical sans-serif, a composite of the system's fonts. Java2D does
     * no glyph fallback for a font loaded from a file, so without this those characters draw as boxes.
     */
    static List<Run> runs(String text, Font font) {
        List<Run> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Font fallback = new Font(Font.SANS_SERIF, font.getStyle(), font.getSize());
        StringBuilder current = new StringBuilder();
        boolean currentOk = true;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            boolean ok = font.canDisplay(cp);
            if (current.length() > 0 && ok != currentOk) {
                out.add(new Run(current.toString(), currentOk ? font : fallback));
                current.setLength(0);
            }
            current.appendCodePoint(cp);
            currentOk = ok;
            i += len;
        }
        if (current.length() > 0) out.add(new Run(current.toString(), currentOk ? font : fallback));
        return out;
    }

    /** The text with a font attribute per run, ready for a measurer or a layout. */
    private static AttributedString attributed(String text, Font font) {
        AttributedString as = new AttributedString(text);
        if (text.isEmpty()) return as;
        int at = 0;
        for (Run r : runs(text, font)) {
            as.addAttribute(TextAttribute.FONT, r.font(), at, at + r.text().length());
            at += r.text().length();
        }
        return as;
    }

    /**
     * @param headerImage the header's image bytes when the header is an image; anything that does not decode is ignored
     * @throws IllegalStateException when the card cannot be drawn
     */
    public byte[] draw(CloudMessage message, Optional<byte[]> headerImage) {
        try {
            BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, WIDTH, HEIGHT);
                leftPanel(g, headerImage.flatMap(PreviewCardRenderer::decode));
                rightSide(g, message.interactive());
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", out)) throw new IllegalStateException("no PNG writer");
            return out.toByteArray();
        } catch (RuntimeException | IOException e) {
            throw new IllegalStateException("preview image could not be generated", e);
        }
    }

    private void leftPanel(Graphics2D g, Optional<BufferedImage> image) {
        if (image.isPresent()) {
            BufferedImage src = image.get();
            // cover: scale so the panel is filled, then centre-crop
            double scale = Math.max((double) PANEL / src.getWidth(), (double) HEIGHT / src.getHeight());
            int w = (int) Math.ceil(src.getWidth() * scale);
            int h = (int) Math.ceil(src.getHeight() * scale);
            int x = (PANEL - w) / 2;
            int y = (HEIGHT - h) / 2;
            g.setClip(0, 0, PANEL, HEIGHT);
            g.drawImage(src, x, y, w, h, null);
            g.setClip(null);
            return;
        }
        g.setColor(GREEN);
        g.fillRect(0, 0, PANEL, HEIGHT);
        // a speech bubble glyph
        g.setColor(Color.WHITE);
        int cx = PANEL / 2;
        int cy = HEIGHT / 2;
        g.fillRoundRect(cx - 90, cy - 70, 180, 130, 40, 40);
        g.fillPolygon(new int[] {cx - 60, cx - 20, cx - 70}, new int[] {cy + 55, cy + 55, cy + 95}, 3);
        g.setColor(GREEN);
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(cx - 50, cy - 25, cx + 50, cy - 25);
        g.drawLine(cx - 50, cy + 5, cx + 20, cy + 5);
    }

    private void rightSide(Graphics2D g, Interactive i) {
        int x = PANEL + PAD;
        int width = WIDTH - x - PAD;
        int y = PAD + 8;
        String body = i == null || i.body() == null || i.body().text() == null ? "" : i.body().text();
        String plain = WhatsAppMarkup.plain(body);
        String headerText = i != null && i.header() != null && "text".equals(i.header().type()) ? i.header().text() : null;

        g.setColor(INK);
        Font titleFont = base.deriveFont(Font.BOLD, 44f);
        String rest;
        Drawn title;
        if (headerText != null) {
            title = paragraph(g, headerText, titleFont, x, y, width, MAX_TITLE_LINES, false);
            rest = plain;
        } else {
            // no header: the body's first lines are the title, and the body goes on from where the title stopped
            title = paragraph(g, plain, titleFont, x, y, width, MAX_TITLE_LINES, true);
            rest = plain.substring(Math.min(title.consumed(), plain.length())).stripLeading();
        }
        y = title.y() + 18;
        g.setColor(MUTED);
        paragraph(g, rest, base.deriveFont(Font.PLAIN, 28f), x, y, width, MAX_BODY_LINES, false);

        List<String> labels = labels(i);
        if (!labels.isEmpty()) pills(g, labels, x, width);
    }

    /** Where a paragraph ended: the y below it and how many characters it showed. */
    private record Drawn(int y, int consumed) {}

    /**
     * Draws up to {@code maxLines} wrapped lines. When the text does not fit, the last line is ellipsised, unless
     * {@code continues}: then it stays whole and the rest is left for the caller to draw.
     */
    private Drawn paragraph(Graphics2D g, String text, Font font, int x, int y, int width, int maxLines,
            boolean continues) {
        if (text == null || text.isBlank()) return new Drawn(y, 0);
        g.setFont(font);
        FontRenderContext frc = g.getFontRenderContext();
        LineBreakMeasurer measurer = new LineBreakMeasurer(attributed(text, font).getIterator(), frc);
        int end = text.length();
        int lines = 0;
        while (measurer.getPosition() < end && lines < maxLines) {
            int start = measurer.getPosition();
            TextLayout layout = measurer.nextLayout(width);
            lines++;
            boolean cut = lines == maxLines && measurer.getPosition() < end && !continues;
            y += (int) layout.getAscent();
            if (cut) {
                String shown = text.substring(start, measurer.getPosition()).stripTrailing();
                while (shown.length() > 1 && new TextLayout(attributed(shown + "\u2026", font).getIterator(), frc).getAdvance() > width) {
                    shown = shown.substring(0, shown.length() - 1);
                }
                new TextLayout(attributed(shown + "\u2026", font).getIterator(), frc).draw(g, x, y);
            } else {
                layout.draw(g, x, y);
            }
            y += (int) (layout.getDescent() + layout.getLeading());
        }
        return new Drawn(y, measurer.getPosition());
    }

    private void pills(Graphics2D g, List<String> labels, int x, int width) {
        Font font = base.deriveFont(Font.BOLD, 26f);
        g.setFont(font);
        FontRenderContext frc = g.getFontRenderContext();
        int h = 56;
        int y = HEIGHT - PAD - h;
        int cx = x;
        int gap = 16;
        for (String label : labels) {
            String shown = label;
            TextLayout layout = new TextLayout(attributed(shown, font).getIterator(), frc);
            while (shown.length() > 1 && layout.getAdvance() + 40 > width) {
                shown = shown.substring(0, shown.length() - 1);
                layout = new TextLayout(attributed(shown, font).getIterator(), frc);
            }
            int w = (int) layout.getAdvance() + 40;
            if (cx + w > x + width) break;
            g.setColor(BLUE);
            g.setStroke(new BasicStroke(3f));
            g.drawRoundRect(cx, y, w, h, h, h);
            layout.draw(g, cx + 20, y + h / 2 + 9);
            cx += w + gap;
        }
    }

    private static List<String> labels(Interactive i) {
        List<String> out = new ArrayList<>();
        if (i == null) return out;
        if (Interactive.BUTTON.equals(i.type())) {
            for (Interactive.Reply r : CloudMessageValidator.replies(i)) out.add(nz(r.title()));
        } else if (Interactive.LIST.equals(i.type())) {
            if (i.action() != null && i.action().button() != null) out.add("☰ " + i.action().button());
        } else if (Interactive.CTA_URL.equals(i.type())) {
            if (i.action() != null && i.action().parameters() != null) out.add("↗ " + nz(i.action().parameters().displayText()));
        } else if (Interactive.LOCATION_REQUEST.equals(i.type())) {
            out.add("Send location");
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Optional<BufferedImage> decode(byte[] bytes) {
        try {
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(bytes)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Font loadFont() {
        try (InputStream in = PreviewCardRenderer.class.getClassLoader().getResourceAsStream("pages/fonts/DejaVuSans.ttf")) {
            if (in == null) throw new IOException("pages/fonts/DejaVuSans.ttf is not on the classpath");
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            log.warn("Bundled font unavailable, preview cards use the JVM's sans-serif: {}", e.toString());
            return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
    }
}
