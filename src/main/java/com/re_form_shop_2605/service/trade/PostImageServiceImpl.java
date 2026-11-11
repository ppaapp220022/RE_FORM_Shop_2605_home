package com.re_form_shop_2605.service.trade;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
/**
 * ─────────────────────────────────────────────────────
 * 작성자: 김민기
 * 작성일: 2026-05-10
 * 설명: 판매글 이미지 저장 서비스 구현체
 * ─────────────────────────────────────────────────────
 */
@Service
public class PostImageServiceImpl implements PostImageService {

    private static final Logger log = LogManager.getLogger(PostImageServiceImpl.class);
    private static final int MAX_POST_IMAGES = 10;
    private static final int MAX_THUMBNAIL_WIDTH = 480;
    private static final int MAX_THUMBNAIL_HEIGHT = 480;
    private static final String TEMP_URL_SEGMENT = "/uploads/post/temp/";
    private static final String FINAL_URL_PREFIX = "/uploads/post/";
    private static final String SEED_URL_PREFIX = "/uploads/post/seed/"; // dummy.sql 시드 판매글 이미지
    private static final String THUMBNAIL_PREFIX = "thumb_";
    private final Path uploadRootPath;

    public PostImageServiceImpl(
            @Value("${spring.servlet.multipart.location}") String uploadRoot
    ) {
        ImageIO.setUseCache(false);
        this.uploadRootPath = Paths.get(uploadRoot).toAbsolutePath().normalize().resolve("post");
    }

    @Override
    // 게시글별 전용 폴더를 만들고 이미지 URL 목록을 반환
    public List<String> savePostImages(Long postId, List<MultipartFile> images) {
        return saveImages(uploadRootPath.resolve(String.valueOf(postId)), "/uploads/post/" + postId + "/", images);
    }

    @Override
    // 판매글 작성 전에 임시 업로드한 이미지를 회원별 temp 폴더에 저장하고 URL 목록을 반환한다.
    public List<String> saveTemporaryPostImages(Long memberId, List<MultipartFile> images) {
        Path tempDirectory = uploadRootPath.resolve("temp").resolve(String.valueOf(memberId));
        String urlPrefix = "/uploads/post/temp/" + memberId + "/";
        return saveImages(tempDirectory, urlPrefix, images);
    }

    @Override
    public List<String> finalizePostImageUrls(Long postId, Long memberId, List<String> imageUrls) {
        if (imageUrls == null) {
            return null;
        }

        Path finalDirectory = uploadRootPath.resolve(String.valueOf(postId));
        String finalUrlPrefix = FINAL_URL_PREFIX + postId + "/";
        List<String> finalizedUrls = new ArrayList<>();

        // 파일을 옮기거나 디렉터리를 만들기 전에 주소부터 모두 검사한다(거부되면 아무것도 남기지 않는다).
        for (String imageUrl : imageUrls) {
            if (StringUtils.hasText(imageUrl)
                    && !imageUrl.startsWith(TEMP_URL_SEGMENT + memberId + "/")
                    && !isOwnedFinalUrl(imageUrl, finalUrlPrefix)) {
                throw new IllegalArgumentException("사용할 수 없는 이미지 주소입니다.");
            }
        }

        try {
            Files.createDirectories(finalDirectory);

            for (String imageUrl : imageUrls) {
                finalizedUrls.add(finalizeSingleImageUrl(postId, memberId, imageUrl, finalDirectory, finalUrlPrefix));
            }
            cleanupEmptyTempDirectory(memberId);
            return finalizedUrls;
        } catch (IOException e) {
            log.error("판매글 이미지 최종 경로 승격 실패. postId={}, memberId={}", postId, memberId, e);
            throw new IllegalStateException("판매글 이미지 처리에 실패했습니다.", e);
        }
    }

    @Override
    public String getThumbnailUrl(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            return imageUrl;
        }

        int slashIndex = imageUrl.lastIndexOf('/');
        if (slashIndex < 0 || slashIndex == imageUrl.length() - 1) {
            return imageUrl;
        }

        String fileName = imageUrl.substring(slashIndex + 1);
        String thumbnailFileName = buildThumbnailFileName(fileName);
        if (thumbnailFileName.equals(fileName)) {
            return imageUrl;
        }
        return imageUrl.substring(0, slashIndex + 1) + thumbnailFileName;
    }

    // 실제 파일 저장 공통 로직을 한 곳으로 모아 게시글 업로드와 임시 업로드가 같은 규칙을 쓰게 한다.
    private List<String> saveImages(Path targetDirectory, String urlPrefix, List<MultipartFile> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }

        List<MultipartFile> validImages = new ArrayList<>();

        for (MultipartFile image : images) {
            if (image != null && !image.isEmpty()) {
                validImages.add(image);
            }
        }

        if (validImages.size() > MAX_POST_IMAGES) {
            throw new IllegalArgumentException("이미지는 최대 10장까지 업로드할 수 있습니다.");
        }

        try {
            Files.createDirectories(targetDirectory);

            List<String> savedImageUrls = new ArrayList<>();

            for (int i = 0; i < validImages.size(); i++) {
                MultipartFile image = validImages.get(i);
                String extension = extractExtension(image.getOriginalFilename());
                String savedFileName = String.format("%02d_%s%s", i + 1, UUID.randomUUID(), extension);
                Path targetPath = targetDirectory.resolve(savedFileName);

                try (InputStream inputStream = image.getInputStream()) {
                    Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                createThumbnailIfPossible(targetPath);
                savedImageUrls.add(urlPrefix + savedFileName);
            }
            return savedImageUrls;
        } catch (IOException e) {
            log.error("판매글 이미지 저장 실패. directory={}", targetDirectory, e);
            throw new IllegalStateException("판매글 이미지 저장에 실패했습니다.", e);
        }
    }

    private boolean isOwnedFinalUrl(String imageUrl, String finalUrlPrefix) {
        // 퍼센트 인코딩(%2e%2e%2f)으로 경로 검사를 우회하지 못하게 '%'도 거부한다.
        if (imageUrl.contains("..") || imageUrl.contains("\\") || imageUrl.contains("%")) {
            return false;
        }
        String fileName;
        if (imageUrl.startsWith(finalUrlPrefix)) {
            fileName = imageUrl.substring(finalUrlPrefix.length());
        } else if (imageUrl.startsWith(SEED_URL_PREFIX)) {
            fileName = imageUrl.substring(SEED_URL_PREFIX.length());
        } else {
            return false;
        }
        return !fileName.isEmpty() && !fileName.contains("/");
    }

    private String finalizeSingleImageUrl(Long postId, Long memberId, String imageUrl, Path finalDirectory, String finalUrlPrefix) throws IOException {
        if (!StringUtils.hasText(imageUrl)) {
            return imageUrl;
        }

        String tempPrefix = TEMP_URL_SEGMENT + memberId + "/";
        if (!imageUrl.startsWith(tempPrefix)) {
            // 이미 승격된 이미지(이 판매글 폴더·시드)는 그대로 쓴다. 다른 주소는 finalizePostImageUrls에서 먼저 걸러진다.
            return imageUrl;
        }

        String fileName = imageUrl.substring(tempPrefix.length());
        Path tempFile = uploadRootPath.resolve("temp").resolve(String.valueOf(memberId)).resolve(fileName).normalize();
        Path finalFile = finalDirectory.resolve(fileName).normalize();

        if (!tempFile.startsWith(uploadRootPath.resolve("temp").resolve(String.valueOf(memberId)).normalize())) {
            throw new IllegalStateException("잘못된 임시 이미지 경로입니다.");
        }

        if (!Files.exists(tempFile)) {
            log.warn("임시 이미지 파일이 없어 기존 URL을 유지합니다. postId={}, memberId={}, imageUrl={}", postId, memberId, imageUrl);
            return imageUrl;
        }

        Files.createDirectories(Objects.requireNonNull(finalFile.getParent()));
        Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
        moveThumbnailIfExists(tempFile, finalFile);
        return finalUrlPrefix + fileName;
    }

    private void cleanupEmptyTempDirectory(Long memberId) throws IOException {
        Path tempDirectory = uploadRootPath.resolve("temp").resolve(String.valueOf(memberId));
        if (!Files.isDirectory(tempDirectory)) {
            return;
        }

        try (var children = Files.list(tempDirectory)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(tempDirectory);
            }
        }
    }

    @Override
    // 게시글별 이미지 폴더 삭제
    public void deletePostImageDirectory(Long postId) {
        Path postDirectory = uploadRootPath.resolve(String.valueOf(postId));

        if (!Files.exists(postDirectory)) {
            return;
        }

        try (var walk = Files.walk(postDirectory)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new IllegalStateException("판매글 이미지 디렉터리 삭제에 실패했습니다.", e);
                        }
                    });
        } catch (IOException e) {
            log.error("판매글 이미지 디렉터리 삭제 실패. postId={}", postId, e);
            throw new IllegalStateException("판매글 이미지 디렉터리 삭제에 실패했습니다.", e);
        }
    }

    // 원본 파일명에서 확장자만 분리
    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "";
        }

        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            return "";
        }

        return originalFilename.substring(dotIndex);
    }

    private void createThumbnailIfPossible(Path originalImagePath) {
        try {
            BufferedImage originalImage = ImageIO.read(originalImagePath.toFile());
            if (originalImage == null) {
                log.warn("썸네일 생성 대상이 이미지가 아닙니다. path={}", originalImagePath);
                return;
            }

            BufferedImage thumbnailImage = resizeImage(originalImage);
            String thumbnailFileName = buildThumbnailFileName(originalImagePath.getFileName().toString());
            if (thumbnailFileName.equals(originalImagePath.getFileName().toString())) {
                return;
            }

            Path thumbnailPath = originalImagePath.resolveSibling(thumbnailFileName);
            writeThumbnail(thumbnailImage, thumbnailPath);
        } catch (IOException e) {
            log.warn("썸네일 생성에 실패해 원본 이미지를 그대로 사용합니다. path={}", originalImagePath, e);
        }
    }

    private BufferedImage resizeImage(BufferedImage originalImage) {
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();
        double scale = Math.min(
                1.0,
                Math.min(
                        (double) MAX_THUMBNAIL_WIDTH / originalWidth,
                        (double) MAX_THUMBNAIL_HEIGHT / originalHeight
                )
        );

        int targetWidth = Math.max(1, (int) Math.round(originalWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(originalHeight * scale));
        int imageType = originalImage.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, imageType);
        Graphics2D graphics = resizedImage.createGraphics();
        try {
            if (!originalImage.getColorModel().hasAlpha()) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return resizedImage;
    }

    private void writeThumbnail(BufferedImage thumbnailImage, Path thumbnailPath) throws IOException {
        String format = resolveThumbnailFormat(thumbnailPath.getFileName().toString());
        Files.createDirectories(Objects.requireNonNull(thumbnailPath.getParent()));

        if ("jpg".equals(format)) {
            writeJpegThumbnail(thumbnailImage, thumbnailPath);
            return;
        }

        try (OutputStream outputStream = Files.newOutputStream(thumbnailPath)) {
            if (!ImageIO.write(thumbnailImage, format, outputStream)) {
                throw new IOException("지원하지 않는 썸네일 포맷입니다. format=" + format);
            }
        }
    }

    private void writeJpegThumbnail(BufferedImage thumbnailImage, Path thumbnailPath) throws IOException {
        BufferedImage jpegImage = new BufferedImage(
                thumbnailImage.getWidth(),
                thumbnailImage.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = jpegImage.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, jpegImage.getWidth(), jpegImage.getHeight());
            graphics.drawImage(thumbnailImage, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG ImageWriter를 찾을 수 없습니다.");
        }

        ImageWriter writer = writers.next();
        try (OutputStream outputStream = Files.newOutputStream(thumbnailPath);
             ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(imageOutputStream);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(0.82f);
            }
            writer.write(null, new IIOImage(jpegImage, null, null), writeParam);
        } finally {
            writer.dispose();
        }
    }

    private void moveThumbnailIfExists(Path sourceOriginalPath, Path targetOriginalPath) throws IOException {
        Path sourceThumbnailPath = sourceOriginalPath.resolveSibling(
                buildThumbnailFileName(sourceOriginalPath.getFileName().toString())
        );
        Path targetThumbnailPath = targetOriginalPath.resolveSibling(
                buildThumbnailFileName(targetOriginalPath.getFileName().toString())
        );

        if (!Files.exists(sourceThumbnailPath)) {
            return;
        }

        Files.createDirectories(Objects.requireNonNull(targetThumbnailPath.getParent()));
        Files.move(sourceThumbnailPath, targetThumbnailPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private String buildThumbnailFileName(String originalFileName) {
        String thumbnailExtension = resolveThumbnailExtension(originalFileName);
        if (thumbnailExtension == null) {
            return originalFileName;
        }

        int dotIndex = originalFileName.lastIndexOf('.');
        String baseName = dotIndex < 0 ? originalFileName : originalFileName.substring(0, dotIndex);
        return THUMBNAIL_PREFIX + baseName + thumbnailExtension;
    }

    private String resolveThumbnailExtension(String originalFileName) {
        String extension = extractExtension(originalFileName).toLowerCase();
        return switch (extension) {
            case ".png" -> ".png";
            case ".jpg", ".jpeg", ".bmp" -> ".jpg";
            case ".gif" -> ".gif";
            default -> null;
        };
    }

    private String resolveThumbnailFormat(String fileName) {
        String extension = extractExtension(fileName).toLowerCase();
        return switch (extension) {
            case ".png" -> "png";
            case ".gif" -> "gif";
            default -> "jpg";
        };
    }
}
