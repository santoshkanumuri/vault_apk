package com.privatevault.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PhotoCropTest {
    @Test fun fullImageKeepsEveryPixel() {
        assertArrayEquals(intArrayOf(0, 0, 4032, 3024), PhotoCrop().pixels(4032, 3024))
    }

    @Test fun rectangularSelectionMapsToImagePixels() {
        assertArrayEquals(intArrayOf(100, 100, 600, 300), PhotoCrop(.1f, .2f, .7f, .8f).pixels(1000, 500))
    }

    @Test fun smallImageAlwaysKeepsAtLeastOnePixel() {
        assertArrayEquals(intArrayOf(0, 0, 1, 1), PhotoCrop(.9f, .9f, 1f, 1f).pixels(1, 1))
    }

    @Test(expected = IllegalArgumentException::class) fun invertedSelectionIsRejected() {
        PhotoCrop(.8f, 0f, .2f, 1f)
    }

    @Test(expected = IllegalArgumentException::class) fun outOfBoundsSelectionIsRejected() {
        PhotoCrop(-.1f, 0f, 1f, 1f)
    }
}
