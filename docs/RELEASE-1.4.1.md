# Private Vault 1.4.1

- Small transparent network artwork on the bottom-right of cards. Unknown networks retain a text label.
- Card number, expiry, and CVV use small copy icons with field-specific accessibility labels. Copying CVV still requires authentication.
- Bundled offline assets from https://github.com/aaronfagan/svg-credit-card-payment-icons, Apache-2.0. Converted SVG paths to Android vectors; Visa and Amex follow card text contrast, and Discover has a light-text variant. Network trademarks belong to their respective owners. License: licenses/payment-icons-LICENSE.txt.
- Camera capture now requests a full-resolution image instead of a preview bitmap. The camera writes to a private cache file shared only through a temporary URI grant. The file and grant are removed after import, cancellation, lock, or next process startup after a crash. The camera application still handles capture and may have its own storage behavior.
- Imported and captured originals are encrypted without re-encoding. Thumbnails decode directly to 480 pixels on the longest edge. The viewer uses up to 4096 pixels for memory limits; this does not change stored resolution.
- Crop and rotate apply EXIF orientation and store edits losslessly as WebP on Android 11+, or PNG on Android 10. Edited photos can take more storage than JPEG. Existing preview-resolution captures need to be retaken.

Physical-device checks: capture fine text on the Samsung camera, open and zoom, rotate/crop, then reopen. Cancel a capture and turn off the screen during another capture; neither should leave a usable pending attachment.
