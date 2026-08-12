# Browser media fixtures

These fixtures are generated, sub-second, synthetic test media with no user or
third-party content. They are stored as Base64 text so their review diff remains
portable.

```bash
ffmpeg -f lavfi -i color=c=blue:s=16x16:r=5 -t 0.4 \
  -c:v libvpx-vp9 -deadline best -an tiny.webm
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -t 0.25 \
  -c:a libopus -b:a 16k -ac 1 tiny.ogg
```

The browser gate decodes them from Blob URLs and requires real `loadedmetadata`
results. They are compatibility fixtures, not product media or benchmark data.
