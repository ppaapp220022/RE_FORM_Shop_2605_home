package com.re_form_shop_2605.eval.listing;

import com.fasterxml.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * ─────────────────────────────────────────────────────
 * 설명: 판매글 eval용 유니폼 이미지 생성기
 *  - cases.jsonl의 render 스펙으로 images/{id}.png를 그린다. 정답(팀명·번호·사이즈·얼룩 등)을 이미지에
 *    직접 그려 넣기 때문에 라벨이 정확하고, 실제 사진의 저작권·개인정보 문제가 없다.
 *  - 생성된 이미지는 커밋해 두고 eval은 그 파일을 읽는다 (폰트가 OS마다 달라 CI에서 다시 그리지 않음)
 *  - 실행: ./gradlew generateListingEvalImages
 * ─────────────────────────────────────────────────────
 */
public class ListingEvalImageGenerator {

    private static final int SIZE = 768;
    private static final String FONT = pickFont();

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/eval/listing/images");
        Files.createDirectories(dir);
        // 실제 사진 셋은 그릴 것이 없으므로 항상 그림 셋만
        for (ListingEvalCase c : ListingEvalCase.load(ListingEvalCase.DATASET, ListingEvalCase.IMAGE_DIR)) {
            BufferedImage image = draw(c.render());
            Path file = dir.resolve(c.id() + ".png");
            ImageIO.write(image, "png", file.toFile());
            System.out.println("wrote " + file);
        }
    }

    static BufferedImage draw(JsonNode r) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setPaint(new GradientPaint(0, 0, new Color(0xEEEEEE), 0, SIZE, new Color(0xD6D6D6)));
        g.fillRect(0, 0, SIZE, SIZE);

        String object = text(r, "object", "jersey");
        switch (object) {
            case "mug" -> drawMug(g, r);
            case "blank" -> { /* 배경만 */ }
            default -> drawJersey(g, r);
        }
        g.dispose();
        return image;
    }

    private static void drawJersey(Graphics2D g, JsonNode r) {
        Color body = Color.decode(text(r, "body", "#FFFFFF"));
        Color trim = Color.decode(text(r, "trim", "#000000"));
        Color ink = contrast(body, trim);
        boolean back = "back".equals(text(r, "view", "front"));

        // 몸판 + 소매
        Polygon shirt = new Polygon(
                new int[]{274, 494, 630, 690, 600, 560, 560, 208, 208, 168, 78, 138},
                new int[]{110, 110, 160, 330, 370, 300, 690, 690, 300, 370, 330, 160}, 12);
        g.setColor(body);
        g.fillPolygon(shirt);
        g.setColor(body.darker());
        g.setStroke(new BasicStroke(3));
        g.drawPolygon(shirt);

        // 소매 끝 트림
        g.setColor(trim);
        g.fillPolygon(new int[]{690, 600, 590, 680}, new int[]{330, 370, 348, 308}, 4);
        g.fillPolygon(new int[]{78, 168, 178, 88}, new int[]{330, 370, 348, 308}, 4);

        // 칼라: 앞면은 V넥, 뒷면은 라운드
        if (back) {
            g.setColor(trim);
            g.fillArc(314, 86, 140, 60, 180, 180);
        } else {
            g.setColor(trim);
            g.fillPolygon(new int[]{314, 454, 384}, new int[]{110, 110, 200}, 3);
            g.setColor(body);
            g.fillPolygon(new int[]{334, 434, 384}, new int[]{110, 110, 176}, 3);
        }

        if (back) {
            centered(g, text(r, "name", null), 384, 300, 64, ink);
            centered(g, text(r, "number", null), 384, 520, 210, ink);
        } else {
            String chest = text(r, "chest", null);
            int chestSize = chest != null && chest.length() > 10 ? 44 : 56;
            centered(g, chest, 384, 270, chestSize, ink);
            centered(g, text(r, "sub", null), 384, 318, 26, ink);
            centered(g, text(r, "number", null), 384, 500, 150, ink);
        }

        String sizeTag = text(r, "sizeTag", null);
        if (sizeTag != null) {
            // 목 안쪽 라벨
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Double(334, 118, 100, 44, 6, 6));
            g.setColor(new Color(0x333333));
            g.setStroke(new BasicStroke(2));
            g.draw(new RoundRectangle2D.Double(334, 118, 100, 44, 6, 6));
            centered(g, "SIZE " + sizeTag, 384, 150, 22, Color.BLACK);
        }

        String defect = text(r, "defect", null);
        if ("stain".equals(defect)) {
            g.setColor(new Color(110, 72, 30, 170));
            g.fill(new Ellipse2D.Double(400, 560, 110, 70));
            g.fill(new Ellipse2D.Double(460, 600, 60, 50));
            g.fill(new Ellipse2D.Double(380, 600, 40, 30));
        } else if ("tear".equals(defect)) {
            Path2D tear = new Path2D.Double();
            tear.moveTo(232, 420);
            double[][] points = {{252, 450}, {236, 480}, {262, 510}, {244, 545}, {270, 580}, {252, 620}};
            for (double[] p : points) {
                tear.lineTo(p[0], p[1]);
            }
            for (int i = points.length - 2; i >= 0; i--) {
                tear.lineTo(points[i][0] + 22, points[i][1]);
            }
            tear.lineTo(254, 420);
            tear.closePath();
            // 찢어진 틈으로 배경이 보이고, 가장자리는 그림자 + 튀어나온 실밥으로 그려 무늬와 구분되게 한다
            g.setColor(new Color(0, 0, 0, 90));
            g.setStroke(new BasicStroke(9));
            g.draw(tear);
            g.setColor(new Color(0xE2E2E2));
            g.fill(tear);
            g.setColor(new Color(0xF5F5F5));
            g.setStroke(new BasicStroke(2));
            for (double[] p : points) {
                g.drawLine((int) p[0], (int) p[1], (int) p[0] - 14, (int) p[1] + 6);
                g.drawLine((int) p[0] + 22, (int) p[1], (int) p[0] + 36, (int) p[1] - 5);
                g.drawLine((int) p[0] + 4, (int) p[1] + 8, (int) p[0] + 17, (int) p[1] + 14);
            }
        }

        String hangTag = text(r, "hangTag", null);
        if (hangTag != null) {
            g.setColor(new Color(0x555555));
            g.setStroke(new BasicStroke(2));
            g.drawLine(640, 345, 660, 410);
            g.setColor(Color.WHITE);
            g.fillRect(600, 410, 150, 90);
            g.setColor(new Color(0x333333));
            g.drawRect(600, 410, 150, 90);
            centered(g, hangTag, 675, 468, hangTag.length() > 5 ? 26 : 30, new Color(0xB91C1C));
        }

        String sticky = text(r, "stickyNote", null);
        if (sticky != null) {
            drawSticky(g, sticky);
        }
    }

    private static void drawMug(Graphics2D g, JsonNode r) {
        Color body = Color.decode(text(r, "body", "#FFFFFF"));
        Color trim = Color.decode(text(r, "trim", "#000000"));
        g.setColor(body);
        g.setStroke(new BasicStroke(34));
        g.drawArc(470, 300, 160, 200, -90, 180);
        g.fillRect(214, 220, 300, 380);
        g.fill(new Ellipse2D.Double(214, 570, 300, 60));
        g.setColor(body.brighter().brighter());
        g.fill(new Ellipse2D.Double(214, 195, 300, 60));
        g.setColor(trim);
        g.fillRect(214, 300, 300, 20);
        centered(g, text(r, "chest", null), 364, 420, 42, contrast(body, trim));
    }

    private static void drawSticky(Graphics2D g, String text) {
        g.setColor(new Color(0, 0, 0, 40));
        g.fillRect(468, 548, 290, 210);
        g.setColor(new Color(0xFDE68A));
        g.fillRect(460, 540, 290, 210);
        g.setColor(new Color(0x1F2937));
        g.setFont(new Font(FONT, Font.BOLD, 26));
        FontMetrics fm = g.getFontMetrics();
        int y = 540 + 20 + fm.getAscent();
        for (String line : text.split("\n")) {
            g.drawString(line, 478, y);
            y += fm.getHeight() + 4;
        }
    }

    private static void centered(Graphics2D g, String text, int cx, int baseline, int size, Color color) {
        if (text == null || text.isBlank()) {
            return;
        }
        g.setFont(new Font(FONT, Font.BOLD, size));
        g.setColor(color);
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, cx - width / 2, baseline);
    }

    /* 바탕색과 대비되는 글자색: 트림 색이 충분히 대비되면 트림, 아니면 흑/백 */
    private static Color contrast(Color body, Color trim) {
        double diff = Math.abs(luminance(body) - luminance(trim));
        if (diff > 0.35) {
            return trim;
        }
        return luminance(body) > 0.5 ? Color.BLACK : Color.WHITE;
    }

    private static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    /* 한글이 들어간 메모지가 있어 한글 폰트를 우선 사용 */
    private static String pickFont() {
        String[] installed = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String candidate : new String[]{"Apple SD Gothic Neo", "Malgun Gothic", "NanumGothic", "Noto Sans CJK KR"}) {
            if (Arrays.asList(installed).contains(candidate)) {
                return candidate;
            }
        }
        return Font.SANS_SERIF;
    }
}
