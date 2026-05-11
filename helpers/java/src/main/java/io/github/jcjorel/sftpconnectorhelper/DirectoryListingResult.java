package io.github.jcjorel.sftpconnectorhelper;

import java.util.List;
import java.util.Map;

/**
 * Result of filtering a directory listing S3 output by regex patterns.
 *
 * <p>Returned by {@link DirectoryListingFilter#filter(String, String, String)}.
 * Contains the filtered file and path entries, plus the original truncation flag
 * from the S3 listing output.</p>
 *
 * @param files     filtered file entries (each map contains at least a {@code "filePath"} key)
 * @param paths     filtered path entries (each map contains at least a {@code "path"} key)
 * @param truncated original truncated flag from the S3 listing output ({@code "true"} or {@code "false"})
 * @see DirectoryListingFilter
 */
public record DirectoryListingResult(
        List<Map<String, Object>> files,
        List<Map<String, Object>> paths,
        String truncated
) {}
