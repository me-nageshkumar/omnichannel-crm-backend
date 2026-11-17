package me.nageshk.omnichannel.service.billing.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.nageshk.omnichannel.db.billing.CreditNote;
import me.nageshk.omnichannel.db.billing.DocumentLine;
import me.nageshk.omnichannel.db.billing.DocumentParty;
import me.nageshk.omnichannel.db.billing.Invoice;
import me.nageshk.omnichannel.db.billing.OrderPayment;
import me.nageshk.omnichannel.db.billing.TaxAmounts;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Draws an invoice or a credit note as an A4 PDF, from the stored document alone: no settings, no customer record,
 * so the file says what the document said when it was issued. One layout serves both.
 */
public final class DocumentPdf {

    private static final String FONT = "pages/fonts/DejaVuSans.ttf";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final float MARGIN = 48;
    private static final float WIDTH = PDRectangle.A4.getWidth();
    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final float RIGHT = WIDTH - MARGIN;
    private static final float BODY = 9.5f;
    private static final float SMALL = 8;
    private static final float LEADING = 13.5f;

    private DocumentPdf() {}

    /** @param zone the zone dates are printed in */
    public static byte[] invoice(Invoice inv, ZoneId zone) {
        return render(page -> {
            page.heading("Tax invoice", inv.getNumber(), inv.getIssuedAt(), zone);
            page.parties(inv.getSupplier(), inv.getCustomer());
            if (inv.getOrderNumber() != null) page.note("Order " + inv.getOrderNumber());
            page.lines(inv.getLines(), inv.getSacCode());
            page.totals(inv.getTaxable(), inv.getTax(), inv.getTotal(), inv.getTotalInWords());
            BigDecimal credit = inv.getAccountCreditUsed() == null ? BigDecimal.ZERO : inv.getAccountCreditUsed();
            if (credit.signum() > 0) {
                page.amount("Account credit used", credit);
                page.amount("Amount paid", inv.getAmountPaid());
            }
            OrderPayment p = inv.getPayment();
            if (p != null && !OrderPayment.NONE.equals(p.method())) {
                page.gap();
                page.note("Paid by " + words(p.method()) + ", reference " + p.reference()
                        + (p.receivedOn() == null ? "" : ", received " + DAY.format(p.receivedOn())));
            }
        });
    }

    public static byte[] creditNote(CreditNote n, ZoneId zone) {
        return render(page -> {
            page.heading("Credit note", n.getNumber(), n.getIssuedAt(), zone);
            page.parties(n.getSupplier(), n.getCustomer());
            page.note("Against invoice " + n.getInvoiceNumber()
                    + (n.getInvoiceDate() == null ? "" : " dated " + DAY.format(n.getInvoiceDate().atZone(zone))));
            if (n.getReason() != null) page.note("Reason: " + n.getReason());
            page.lines(n.getLines(), n.getSacCode());
            page.totals(n.getTaxable(), n.getTax(), n.getTotal(), n.getTotalInWords());
            page.gap();
            page.note("The total of this credit note was added to the customer's account credit.");
        });
    }

    /** 1234567.5 as 12,34,567.50: lakhs and crores, as amounts are written in India. */
    static String money(BigDecimal amount) {
        BigDecimal a = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        String plain = a.abs().toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        String paise = plain.substring(plain.indexOf('.'));
        StringBuilder grouped = new StringBuilder();
        int n = whole.length();
        if (n > 3) {
            String head = whole.substring(0, n - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) grouped.append(',');
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(whole, n - 3, n);
        } else {
            grouped.append(whole);
        }
        return (a.signum() < 0 ? "-" : "") + grouped + paise;
    }

    private static String words(String method) {
        return switch (method) {
            case "BANK_TRANSFER" -> "bank transfer";
            case "UPI" -> "UPI";
            case "CHEQUE" -> "cheque";
            case "CASH" -> "cash";
            default -> "another method";
        };
    }

    private interface Body {
        void draw(Sheet sheet) throws IOException;
    }

    private static byte[] render(Body body) {
        try (PDDocument doc = new PDDocument(); InputStream in = DocumentPdf.class.getClassLoader().getResourceAsStream(FONT)) {
            if (in == null) throw new IOException(FONT + " is not on the classpath");
            Sheet sheet = new Sheet(doc, PDType0Font.load(doc, in));
            body.draw(sheet);
            sheet.footer();
            sheet.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("could not draw the PDF", e);
        }
    }

    /** The pages being written: a cursor that moves down, and onto a new page when one is full. */
    private static final class Sheet {
        private final PDDocument doc;
        private final PDType0Font font;
        private final Map<Integer, Boolean> drawable = new HashMap<>();
        private PDPageContentStream out;
        private float y;

        Sheet(PDDocument doc, PDType0Font font) throws IOException {
            this.doc = doc;
            this.font = font;
            newPage();
        }

        private void newPage() throws IOException {
            if (out != null) out.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            out = new PDPageContentStream(doc, page);
            y = HEIGHT - MARGIN;
        }

        /** Makes sure {@code height} more fits on the page, starting a new one if not. */
        private void room(float height) throws IOException {
            if (y - height < MARGIN + 24) newPage();
        }

        void close() throws IOException {
            out.close();
        }

        /** Text the font can draw: anything else (another script, a control character) becomes a question mark. */
        private String safe(String raw) {
            if (raw == null) return "";
            StringBuilder ok = new StringBuilder();
            raw.codePoints().forEach(cp -> {
                if (Character.isISOControl(cp)) {
                    ok.append(' ');
                } else if (drawable.computeIfAbsent(cp, this::canDraw)) {
                    ok.appendCodePoint(cp);
                } else {
                    ok.append('?');
                }
            });
            return ok.toString();
        }

        private boolean canDraw(int codePoint) {
            try {
                font.encode(new String(Character.toChars(codePoint)));
                return true;
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                return false;
            }
        }

        private float width(String text, float size) throws IOException {
            return font.getStringWidth(text) / 1000 * size;
        }

        private void at(float x, float size, String text) throws IOException {
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(x, y);
            out.showText(text);
            out.endText();
        }

        private void rightAt(float size, String text) throws IOException {
            at(RIGHT - width(text, size), size, text);
        }

        private void rule() throws IOException {
            out.setLineWidth(0.5f);
            out.moveTo(MARGIN, y);
            out.lineTo(RIGHT, y);
            out.stroke();
        }

        /** Splits text into lines no wider than {@code max}; a word longer than a line is cut. */
        private List<String> wrap(String raw, float size, float max) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String paragraph : (raw == null ? "" : raw).split("\\R")) {
                StringBuilder line = new StringBuilder();
                for (String word : safe(paragraph).trim().split("\\s+")) {
                    while (width(word, size) > max && word.length() > 1) {
                        int cut = word.length() - 1;
                        while (cut > 1 && width(word.substring(0, cut), size) > max) cut--;
                        if (line.length() > 0) {
                            lines.add(line.toString());
                            line.setLength(0);
                        }
                        lines.add(word.substring(0, cut));
                        word = word.substring(cut);
                    }
                    String tried = line.length() == 0 ? word : line + " " + word;
                    if (width(tried, size) > max && line.length() > 0) {
                        lines.add(line.toString());
                        line = new StringBuilder(word);
                    } else {
                        line = new StringBuilder(tried);
                    }
                }
                if (line.length() > 0) lines.add(line.toString());
            }
            return lines;
        }

        void heading(String title, String number, Instant issuedAt, ZoneId zone) throws IOException {
            y -= 16;
            at(MARGIN, 18, title);
            rightAt(11, safe(number));
            y -= 15;
            rightAt(BODY, "Date: " + DAY.format(issuedAt.atZone(zone)));
            y -= 12;
            rule();
            y -= 6;
        }

        void parties(DocumentParty supplier, DocumentParty customer) throws IOException {
            float half = (RIGHT - MARGIN) / 2 - 12;
            float top = y;
            float left = party("From", supplier, MARGIN, half);
            y = top;
            float right = party("Billed to", customer, MARGIN + half + 24, half);
            y = Math.min(left, right) - 8;
            rule();
            y -= 6;
        }

        /** One party's block; returns where it ended. */
        private float party(String label, DocumentParty p, float x, float max) throws IOException {
            y -= LEADING;
            at(x, SMALL, label);
            y -= LEADING + 1;
            for (String line : wrap(p == null ? "" : p.legalName(), 11, max)) {
                at(x, 11, line);
                y -= LEADING + 1;
            }
            if (p == null) return y;
            for (String line : wrap(p.address(), BODY, max)) {
                at(x, BODY, line);
                y -= LEADING;
            }
            if (p.stateCode() != null) {
                at(x, BODY, safe("State: " + (p.stateName() == null ? "" : p.stateName() + " ") + "(" + p.stateCode() + ")"));
                y -= LEADING;
            }
            at(x, BODY, safe("GSTIN: " + (p.gstin() == null || p.gstin().isBlank() ? "not registered" : p.gstin())));
            y -= LEADING;
            return y;
        }

        void note(String text) throws IOException {
            for (String line : wrap(text, BODY, RIGHT - MARGIN)) {
                room(LEADING);
                y -= LEADING;
                at(MARGIN, BODY, line);
            }
        }

        void gap() {
            y -= 6;
        }

        void lines(List<DocumentLine> lines, String sacCode) throws IOException {
            y -= 10;
            tableHead();
            for (DocumentLine l : lines) {
                List<String> wrapped = wrap(l.description(), BODY, RIGHT - MARGIN - 110);
                if (y - LEADING * Math.max(1, wrapped.size()) < MARGIN + 24) {
                    newPage();
                    tableHead();
                }
                boolean first = true;
                for (String line : wrapped.isEmpty() ? List.of("") : wrapped) {
                    y -= LEADING;
                    at(MARGIN, BODY, line);
                    if (first) rightAt(BODY, money(l.amount()));
                    first = false;
                }
            }
            y -= 6;
            rule();
            if (sacCode != null && !sacCode.isBlank()) {
                room(LEADING);
                y -= LEADING;
                at(MARGIN, SMALL, safe("HSN/SAC: " + sacCode));
            }
        }

        private void tableHead() throws IOException {
            y -= LEADING;
            at(MARGIN, SMALL, "Description");
            rightAt(SMALL, "Amount (INR)");
            y -= 5;
            rule();
            y -= 2;
        }

        void totals(BigDecimal taxable, TaxAmounts tax, BigDecimal total, String inWords) throws IOException {
            room(LEADING * 7);
            y -= 4;
            amount("Taxable value", taxable);
            if (tax != null) {
                if (tax.interState()) {
                    amount("IGST " + percent(tax.ratePercent()), tax.igst());
                } else {
                    String half = percent(tax.ratePercent().divide(BigDecimal.valueOf(2)));
                    amount("CGST " + half, tax.cgst());
                    amount("SGST " + half, tax.sgst());
                }
            }
            y -= LEADING + 3;
            at(RIGHT - 230, 11, "Total");
            rightAt(11, "₹ " + money(total));
            if (inWords != null) {
                y -= 4;
                note(inWords);
            }
        }

        void amount(String label, BigDecimal value) throws IOException {
            room(LEADING);
            y -= LEADING;
            at(RIGHT - 230, BODY, label);
            rightAt(BODY, money(value));
        }

        private static String percent(BigDecimal rate) {
            return rate.stripTrailingZeros().toPlainString() + "%";
        }

        void footer() throws IOException {
            room(LEADING * 3);
            y -= LEADING * 2;
            at(MARGIN, SMALL, "This is a computer-generated document.");
        }
    }
}
