# Photography management

Upload photos directly at **https://yogeshmuppiri.com/admin/photography.html** after
deployment. Sign in with username `admin` and your `GALLERY_ADMIN_PASSWORD`.
Select photos, optionally add descriptions and choose display order, then click
**Upload & publish**. Photos are automatically stored in Cloudinary and published
to the existing gallery. You do not need to open Cloudinary for routine uploads.

The admin page supports batches of up to 20 JPEG, PNG or WebP photos (20 MB each),
previews, per-photo results and retrying failed uploads without resending successful
ones. Convert HEIC files to JPEG before uploading. Cloudinary account limits may
be lower than the site's 20 MB limit. An ambiguous network failure can mean a photo
was uploaded despite the missing response; check the gallery before retrying it.

The Spring Boot endpoint `GET /api/gallery` reads public uploaded images tagged
`portfolio-photography` from Cloudinary. The admin upload endpoint applies this tag
automatically. No HTML edits or site deployments are needed when photos change.

The configured Render cloud name is `bfj0lu5i`. Its API key and API secret must
belong to that same Cloudinary product environment.

## Image appearance

Uploads use the same responsive masonry layout as the original gallery: one column
on phones, two from 640px, and three from 1024px, with the existing spacing, rounded
corners and hover effect. Portrait and landscape images retain their natural aspect
ratios (`width: 100%; height: auto`). Cloudinary's `c_limit,w_1600` transformation
limits large images without cropping or stretching them; `f_auto,q_auto` handles
delivery format and compression. Original uploads remain in Cloudinary.

## One-time setup

1. Create/sign in to your Cloudinary account and select a product environment.
2. Find its cloud name, API key and API secret in Cloudinary Settings / API Keys.
3. Add `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, and `CLOUDINARY_API_SECRET`
   to the Render web service's Environment settings. Keep secrets out of Git and browser code.
   `CLOUDINARY_GALLERY_TAG` defaults to `portfolio-photography`.
4. Add `GALLERY_ADMIN_PASSWORD`: choose a unique password of at least 10 characters,
   no more than 72 UTF-8 bytes. The admin login is disabled when this value is absent.
5. Upload the existing photos using the migration below, or through the admin page
   after deployment. When Cloudinary is configured, only its tagged photos appear.
6. Deploy the updated application. Visit `/admin/photography.html`, sign in as `admin`,
   publish a photo and open `/photography.html` to verify it.

Authentication uses an HttpOnly session cookie, a 30-minute idle timeout, hashed
server-side password verification and CSRF protection for login, upload and logout.
Use the HTTPS site URL. API credentials never go to the browser. Sessions are in
memory, so a deploy/restart signs you out. The login page is publicly accessible;
the upload API requires an authenticated administrator. Changing the Render admin
password and restarting resets access.

For local development, set the same variables in the process environment, then run
`mvn spring-boot:run`. `.env.example` documents the names; Spring Boot and the migration
script do **not** automatically load `.env` files.

## Migrate the existing gallery

From the repository root, run `python scripts/migrate_photography.py` for a dry run.
Set the three Cloudinary environment variables in your terminal, then run:

```text
python scripts/migrate_photography.py --upload
```

The script uploads the 17 unique existing images with their alt text and display order.
Public IDs use `portfolio/photography/<original filename without extension>`.
Reruns skip existing IDs without overwriting files or edits. If an existing asset is
missing its tag/context, update those fields in Media Library. Local files remain intact.

## Upload and order photos

Use `/admin/photography.html` for uploads. Descriptions are optional and used as
accessible alt text. Omitted or blank descriptions default to "Photography by Yogesh Muppiri".
Optional display order accepts whole numbers, smaller first.
Ordered photos appear first; unordered photos follow, newest first. Successful
uploads invalidate the server gallery cache so opening or refreshing the gallery
fetches the updated list immediately. An already open gallery refreshes every five
minutes. Original aspect ratios and the masonry layout are preserved.

This admin page handles uploads; editing or removing previously published photos
is not included. Those operations can still be performed in Cloudinary:

- Upload an image in Cloudinary Media Library using public (`upload`) delivery.
- Add the tag `portfolio-photography` to publish it. Untagged images stay out of the gallery.
- Set contextual metadata `alt` to describe the photo; `caption` is used if `alt` is empty.
- Optional contextual metadata `gallery_order` controls ordering (smaller numbers first).
  Ordered photos come first; photos without an order follow, newest first.
- Remove the publication tag to unpublish, or delete the image in Cloudinary.
- Replacing an image updates its versioned delivery URL on the next refresh.

The server caches listings for five minutes to conserve Cloudinary Admin API quota.
Changes made directly in Cloudinary appear after that cache expires. An already open
visible page refreshes every five minutes, so direct Cloudinary edits may take up to
roughly ten minutes to catch up. Admin-page uploads invalidate the cache immediately.
Image delivery uses automatic format/quality and a maximum width of 1600 pixels.

With all three credentials absent, the app serves the original gallery from
`photography-seed.json`. Partial configuration returns an unavailable response.
A configured but empty Cloudinary gallery stays empty: it does not resurrect local images.
During upstream outages, the server can serve its last successful list for up to one hour,
then returns a generic 503 with a retry button on the page. Restarting clears this memory cache.
Unpublishing may therefore be delayed during an outage; it also does not revoke an already
public image URL. Private/authenticated assets are excluded even if tagged.

API reference: https://cloudinary.com/documentation/admin_api#get_resources_by_tag
Upload reference: https://cloudinary.com/documentation/image_upload_api_reference#upload
