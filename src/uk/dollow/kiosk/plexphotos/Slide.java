// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

/** A photo that has been downloaded and sized to fit the inline document budget. */
final class Slide {
    final Photo photo;
    final String jpegBase64;
    final int width;
    final int height;
    /** Small copy used as the outgoing layer when the next slide crossfades in. May be null. */
    final String thumbBase64;
    /** Fill decision made when this slide was rendered, reused when it becomes the outgoing layer. */
    boolean cover;

    Slide(Photo photo, String jpegBase64, int width, int height, String thumbBase64) {
        this.photo = photo;
        this.jpegBase64 = jpegBase64;
        this.width = width;
        this.height = height;
        this.thumbBase64 = thumbBase64;
    }
}
