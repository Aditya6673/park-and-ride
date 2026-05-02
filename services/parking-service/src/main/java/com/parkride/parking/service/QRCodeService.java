package com.parkride.parking.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.parkride.parking.domain.Booking;
import com.parkride.parking.exception.ParkingException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Generates and validates signed JWT QR codes for parking gate access.
 *
 * <p>QR token JWT claims:
 * <ul>
 *   <li>{@code sub} — bookingId (String)</li>
 *   <li>{@code uid} — userId (UUID)</li>
 *   <li>{@code sid} — slotId (UUID)</li>
 *   <li>{@code lid} — lotId (UUID)</li>
 *   <li>{@code exp} — endTime + 1 hour grace period</li>
 * </ul>
 *
 * <p>The gate scanner verifies the signature offline using the shared secret,
 * then checks the booking status via the DB only if the JWT is valid.
 * This allows gate validation to work even during brief network interruptions.
 */
@Slf4j
@Service
public class QRCodeService {

    private static final int QR_SIZE_PX         = 400;
    private static final int GRACE_PERIOD_HOURS  = 1;

    private final SecretKey signingKey;

    public QRCodeService(@Value("${security.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // Pad to at least 32 bytes for HMAC-SHA256 minimum key length
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    // ── Token generation ──────────────────────────────────────────────────

    /**
     * Issues a signed JWT for the given booking.
     * The token expires {@code endTime + 1 hour} to accommodate late exits.
     */
    public String generateQrToken(Booking booking) {
        Instant expiry = booking.getEndTime().plus(GRACE_PERIOD_HOURS, ChronoUnit.HOURS);

        return Jwts.builder()
                .subject(booking.getId().toString())
                .claim("uid", booking.getUserId().toString())
                .claim("sid", booking.getSlot().getId().toString())
                .claim("lid", booking.getSlot().getLot().getId().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    // ── Image generation ──────────────────────────────────────────────────

    /**
     * Renders the QR token as a 400×400 PNG byte array.
     * Uses ZXing's {@link QRCodeWriter} with H-level error correction
     * (30% damage tolerance — important for printed/worn QR stickers).
     */
    public byte[] generateQrImage(String token) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                    EncodeHintType.MARGIN, 2
            );
            BitMatrix matrix = writer.encode(token, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();

        } catch (WriterException | IOException e) {
            throw new ParkingException("Failed to generate QR code image", e);
        }
    }

    // ── Token validation ──────────────────────────────────────────────────

    /**
     * Validates the QR token signature and extracts the booking ID.
     *
     * @param token raw JWT from QR scan
     * @return bookingId UUID if valid
     * @throws ParkingException if the token is invalid or expired
     */
    public UUID validateAndExtractBookingId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return UUID.fromString(claims.getSubject());
        } catch (Exception e) {
            throw new ParkingException("Invalid or expired QR token: " + e.getMessage(), e);
        }
    }
}
