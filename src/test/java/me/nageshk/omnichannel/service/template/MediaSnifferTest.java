package me.nageshk.omnichannel.service.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MediaSnifferTest {

    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        byte[] out = new byte[0];
        for (byte[] p : parts) {
            byte[] n = Arrays.copyOf(out, out.length + p.length);
            System.arraycopy(p, 0, n, out.length, p.length);
            out = n;
        }
        return out;
    }

    private static byte[] ftyp(String brand) {
        return concat(bytes(0, 0, 0, 0x18), ascii("ftyp" + brand), new byte[12]);
    }

    private static Optional<String> detect(byte[] head, String name) {
        return MediaSniffer.detect(head, name);
    }

    @Test
    void jpeg() {
        assertThat(detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10), "a.jpg")).contains("image/jpeg");
    }

    @Test
    void png() {
        assertThat(detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0), "a.png")).contains("image/png");
    }

    @Test
    void pdf() {
        assertThat(detect(ascii("%PDF-1.7\n"), "a.pdf")).contains("application/pdf");
    }

    @Test
    void mp4() {
        assertThat(detect(ftyp("isom"), "a.mp4")).contains("video/mp4");
        assertThat(detect(ftyp("mp42"), "a.mp4")).contains("video/mp4");
    }

    @Test
    void threeGpp() {
        assertThat(detect(ftyp("3gp4"), "a.3gp")).contains("video/3gpp");
    }

    @Test
    void m4a() {
        assertThat(detect(ftyp("M4A "), "a.m4a")).contains("audio/mp4");
    }

    @Test
    void ogg() {
        byte[] opus = concat(ascii("OggS"), new byte[24], ascii("OpusHead"), new byte[8]);
        assertThat(detect(opus, "a.ogg")).contains("audio/ogg");
        assertThat(MediaSniffer.isOpus(opus)).isTrue();
        byte[] vorbis = concat(ascii("OggS"), new byte[24], ascii("\u0001vorbis"), new byte[8]);
        assertThat(detect(vorbis, "a.ogg")).contains("audio/ogg");
        assertThat(MediaSniffer.isOpus(vorbis)).isFalse();
    }

    @Test
    void mp3() {
        assertThat(detect(ascii("ID3\u0004\u0000\u0000"), "a.mp3")).contains("audio/mpeg");
        assertThat(detect(bytes(0xFF, 0xFB, 0x90, 0x00), "a.mp3")).contains("audio/mpeg");
        assertThat(detect(bytes(0xFF, 0xE3, 0x90, 0x00), "a.mp3")).contains("audio/mpeg");
    }

    @Test
    void aac() {
        assertThat(detect(bytes(0xFF, 0xF1, 0x50, 0x80), "a.aac")).contains("audio/aac");
        assertThat(detect(bytes(0xFF, 0xF9, 0x50, 0x80), "a.aac")).contains("audio/aac");
    }

    @Test
    void zipFamily() {
        byte[] pk = bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0);
        assertThat(detect(pk, "a.docx")).contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(detect(pk, "a.XLSX")).contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(detect(pk, "a.pptx")).contains("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(detect(pk, "a.zip")).contains("application/zip");
        assertThat(detect(pk, "a.jar")).isEmpty();
        assertThat(detect(pk, "a.txt")).isEmpty();
    }

    @Test
    void oleFamily() {
        byte[] ole = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
        assertThat(detect(ole, "a.doc")).contains("application/msword");
        assertThat(detect(ole, "a.xls")).contains("application/vnd.ms-excel");
        assertThat(detect(ole, "a.ppt")).contains("application/vnd.ms-powerpoint");
        assertThat(detect(ole, "a.msi")).isEmpty();
    }

    @Test
    void textByExtension() {
        assertThat(detect(ascii("hello, world\n"), "a.txt")).contains("text/plain");
        assertThat(detect(ascii("a,b\n1,2\n"), "a.CSV")).contains("text/csv");
        assertThat(detect(ascii("hello"), "a.md")).isEmpty();
        assertThat(detect(ascii("hello"), null)).isEmpty();
    }

    @Test
    void txtWithNulIsRejected() {
        assertThat(detect(bytes('h', 'i', 0, 'x'), "a.txt")).isEmpty();
        assertThat(detect(bytes('a', ',', 'b', 0), "a.csv")).isEmpty();
    }

    @Test
    void rejectsExeAndHtml() {
        assertThat(detect(bytes('M', 'Z', 0x90, 0, 3, 0, 0, 0), "a.exe")).isEmpty();
        assertThat(detect(bytes('M', 'Z', 0x90, 0, 3, 0, 0, 0), "a.png")).isEmpty();
        assertThat(detect(ascii("<html><script>alert(1)</script>"), "a.html")).isEmpty();
        assertThat(detect(ascii("<html><script>alert(1)</script>"), "a.png")).isEmpty();
    }

    @Test
    void readsOnlyTheFirst64Bytes() {
        byte[] head = concat(new byte[64], ascii("OpusHead"));
        assertThat(MediaSniffer.isOpus(concat(ascii("OggS"), head))).isFalse();
        assertThat(detect(concat(ascii("a".repeat(64)), new byte[8]), "a.txt")).contains("text/plain");
    }
}
