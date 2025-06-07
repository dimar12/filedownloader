package com.example.filedownloader.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadRequest {

    @NotBlank(message = "URL не может быть пустым")
    @Pattern(regexp = "^https?://.*", message = "URL должен начинаться с http:// или https://")
    private String url;

    @NotBlank(message = "Имя файла не может быть пустым")
    @Size(max = 255, message = "Имя файла не может быть длиннее 255 символов")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Имя файла содержит недопустимые символы")
    private String filename;
}