package io.github.jcjorel.sftpconnectorhelper;

import java.util.List;
import java.util.Map;

/**
 * Result of filtering a directory listing S3 output by regex patterns.
 *
 * @param files filtered file entries (each containing at least "filePath")
 * @param paths filtered path entries (each containing at least "path")
 * @param truncated original truncated flag from the S3 listing output (string "true"/"false")
 */
public record DirectoryListingResult(
        List<Map<String, Object>> files,
        List<Map<String, Object>> paths,
        String truncated
) {}
