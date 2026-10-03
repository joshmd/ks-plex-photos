// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

/** One photo from a Plex library. Only the fields the slideshow needs. */
final class Photo {
    final String ratingKey;
    final String partKey;
    final String title;
    final String album;
    /** Taken date as yyyy-MM-dd, or empty when Plex has none. */
    final String date;

    Photo(String ratingKey, String partKey, String title, String album, String date) {
        this.ratingKey = ratingKey;
        this.partKey = partKey;
        this.title = title == null ? "" : title;
        this.album = album == null ? "" : album;
        this.date = date == null ? "" : date;
    }

    Photo withAlbum(String albumTitle) {
        return album.isEmpty() ? new Photo(ratingKey, partKey, title, albumTitle, date) : this;
    }
}
