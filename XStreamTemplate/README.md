# XStream Template

Standalone CloudStream provider template for the xstream.cc domain.

This module is isolated from InternetArchive, VKVideo, WebSourceProvider,
WikimediaCommons, and YandexVideo.

The root settings.gradle.kts automatically discovers this module because it
contains its own build.gradle.kts.

The referenced website is an adult-content service, so this repository keeps
this module as a clean, non-scraping template. It does not copy the site's
frontend, reproduce explicit content, or extract adult media.

The module is ready as a separate CloudStream template and can be reused for
an authorized, non-adult source using its documented/public API.
