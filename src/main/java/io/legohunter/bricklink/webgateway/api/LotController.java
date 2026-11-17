package io.legohunter.bricklink.webgateway.api;

import java.io.IOException;

import io.legohunter.bricklink.webgateway.browser.BrowserSession.FilePart;
import io.legohunter.bricklink.webgateway.lot.LotService;
import io.legohunter.bricklink.webgateway.lot.LotState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The lot endpoints. Each does one named task; there is no general "post this form" endpoint.
 * All of them need the caller token, the reads included: a read uses the signed-in session.
 */
@RestController
public class LotController {

    private final LotService lots;
    private final TokenAuthorizer tokens;

    private final DataSize maxImageSize;

    public LotController(LotService lots, TokenAuthorizer tokens,
                         @Value("${bricklink.web-gateway.max-image-size:2500000B}") DataSize maxImageSize) {
        this.lots = lots;
        this.tokens = tokens;
        this.maxImageSize = maxImageSize;
    }

    public record LotImage(long lotId, boolean customImage, String thumbnailUrl) {
        static LotImage of(LotState lot) {
            return new LotImage(lot.lotId(), lot.customImage(), lot.thumbnailUrl());
        }
    }

    public record LotExtendedDescription(long lotId, String extendedDescription) {
        static LotExtendedDescription of(LotState lot) {
            return new LotExtendedDescription(lot.lotId(), lot.extendedDescription());
        }
    }

    public record ExtendedDescriptionRequest(String extendedDescription) {
    }

    @GetMapping("/lots/{lotId}/image")
    public LotImage image(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                          @PathVariable long lotId) {
        tokens.requireCaller(authorization);
        return LotImage.of(lots.read(lotId));
    }

    /**
     * Sets or replaces the custom image. Multipart field {@code image}: a JPEG or GIF file, the only
     * formats BrickLink's upload form takes.
     *
     * <p>A replace deletes the existing image first, so a file BrickLink would then reject must be
     * turned away before anything is deleted. Hence the format is read from the file's first bytes,
     * not from the declared content type, and the size is limited here.
     */
    @PutMapping(path = "/lots/{lotId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LotImage putImage(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                             @PathVariable long lotId, @RequestPart("image") MultipartFile image) {
        tokens.requireCaller(authorization);
        byte[] content;
        try {
            content = image.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("The uploaded image could not be read");
        }
        if (content.length > maxImageSize.toBytes()) {
            throw new InvalidRequestException("The image is " + content.length + " bytes; the largest accepted is "
                    + maxImageSize.toBytes() + " (BrickLink turns away large files)");
        }
        FilePart file;
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            file = new FilePart("lot-" + lotId + ".jpg", MediaType.IMAGE_JPEG_VALUE, content);
        } else if (startsWith(content, 'G', 'I', 'F', '8')) {
            file = new FilePart("lot-" + lotId + ".gif", MediaType.IMAGE_GIF_VALUE, content);
        } else {
            throw new InvalidRequestException("The image must be a JPEG or GIF file; BrickLink takes no other format");
        }
        return LotImage.of(lots.putImage(lotId, file));
    }

    private static boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    @DeleteMapping("/lots/{lotId}/image")
    public LotImage deleteImage(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                @PathVariable long lotId) {
        tokens.requireCaller(authorization);
        return LotImage.of(lots.deleteImage(lotId));
    }

    @GetMapping("/lots/{lotId}/extended-description")
    public LotExtendedDescription extendedDescription(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long lotId) {
        tokens.requireCaller(authorization);
        return LotExtendedDescription.of(lots.read(lotId));
    }

    /** Sets or replaces the Extended Description. The answer carries the text BrickLink stored. */
    @PutMapping(path = "/lots/{lotId}/extended-description", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LotExtendedDescription putExtendedDescription(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable long lotId, @RequestBody ExtendedDescriptionRequest request) {
        tokens.requireCaller(authorization);
        if (request == null || request.extendedDescription() == null) {
            throw new InvalidRequestException("The body needs an \"extendedDescription\" text (empty to clear it)");
        }
        return LotExtendedDescription.of(lots.putExtendedDescription(lotId, request.extendedDescription()));
    }

    /** The request itself is wrong. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
