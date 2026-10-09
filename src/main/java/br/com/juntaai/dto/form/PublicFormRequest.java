package br.com.juntaai.dto.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PublicFormRequest(
        @NotBlank @Size(max = 120) @Pattern(regexp = "[^\\p{Cc}\\u2028\\u2029]*") String name,
        @NotBlank @Size(max = 254) @Email
        @Pattern(regexp = "[^\\p{Cc}\\u2028\\u2029]*") String email,
        @NotBlank @Size(max = 32) @Pattern(regexp = "[^\\p{Cc}\\u2028\\u2029]*") String subject,
        @Size(max = 160) @Pattern(regexp = "[^\\p{Cc}\\u2028\\u2029]*") String subjectOther,
        @NotBlank @Size(max = 10000) String message
) {
    public PublicFormRequest {
        name = normalizeHeader(name);
        email = normalizeHeader(email);
        subject = normalizeHeader(subject);
        subjectOther = "outro".equals(subject) ? normalizeHeader(subjectOther) : null;
        message = message == null ? null : message.strip();
    }

    private static String normalizeHeader(String value) {
        // Validate control characters even at the edges, before removing whitespace.
        if (value == null || value.codePoints().anyMatch(c -> Character.isISOControl(c)
                || c == 0x2028 || c == 0x2029)) {
            return value;
        }
        return value.strip();
    }
}
