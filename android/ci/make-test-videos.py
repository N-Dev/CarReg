#!/usr/bin/env python3
"""Makes the short videos the on-device tests read (app/src/androidTest/assets/videos).

traffic.mp4        960x540, 30 fps, 11 s: the synthetic road of ScreensTest.countingSessionResultsAndReport,
                   a car left to right at 40 km/h and one right to left at 60 km/h (lines 20 m apart at
                   x = 0.35 and 0.65), recorded "2026-09-29 07:10 UTC".
plates.mp4         1280x720, 30 fps, 4 s: the sample Irish car (241-D-12345), drifting slowly.
plates-portrait.mp4  The same car filmed upright on a phone held portrait: stored on its side with a
                   rotation of 90 degrees, as phones do.

Needs Pillow and ffmpeg (with libx264). Run from anywhere: python3 android/ci/make-test-videos.py
"""
import os
import subprocess
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(REPO, "tests", "e2e", "assets")
OUT = os.path.join(REPO, "android", "app", "src", "androidTest", "assets", "videos")


def crop(im, x, y, w, h):
    return im.crop((x, y, min(x + w, im.width), min(y + h, im.height)))


def encode(name, size, fps, frames, rotation=None, date=None):
    args = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{size[0]}x{size[1]}", "-r", str(fps)]
    if rotation is not None:
        args += ["-display_rotation", str(rotation), "-noautorotate"]
    args += ["-i", "-", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-crf", "26", "-g", str(fps), "-movflags", "+faststart"]
    if date:
        args += ["-metadata", f"creation_time={date}"]
    args.append(os.path.join(OUT, name))
    p = subprocess.Popen(args, stdin=subprocess.PIPE)
    for f in frames:
        p.stdin.write(f.convert("RGB").tobytes())
    p.stdin.close()
    assert p.wait() == 0
    print(name, os.path.getsize(os.path.join(OUT, name)), "bytes")


def traffic():
    car_a = crop(Image.open(os.path.join(ASSETS, "car_ie.jpg")), 140, 125, 1220, 690).resize((300, 170), Image.BILINEAR)
    car_b = crop(Image.open(os.path.join(ASSETS, "two_cars.jpg")), 1690, 118, 1190, 690).resize((220, 128), Image.BILINEAR)
    fps = 30
    for i in range(11 * fps):
        t = i / fps
        im = Image.new("RGB", (960, 540), (0x9A, 0xA7, 0xB4))
        im.paste((0x3B, 0x3E, 0x44), (0, 250, 960, 470))
        im.paste((0xC9, 0xC4, 0xB8), (0, 470, 960, 540))
        im.paste((0xD8, 0xD9, 0xDA), (0, 357, 960, 362))
        if t >= 5.2:
            im.paste(car_b, (round(960 - 240 * (t - 5.2)), 350 - 128))
        im.paste(car_a, (round(-300 + 160 * t), 462 - 170))
        yield im


def car_frame(w, h, t):
    car = Image.open(os.path.join(ASSETS, "car_ie.jpg"))
    scale = min(w / car.width, h / car.height) * 0.92
    c = car.resize((round(car.width * scale), round(car.height * scale)), Image.BILINEAR)
    im = Image.new("RGB", (w, h), (0x6B, 0x70, 0x78))
    dx = round(12 * (t - 2))  # a slow drift, so frames differ
    im.paste(c, ((w - c.width) // 2 + dx, (h - c.height) // 2))
    return im


def plates():
    fps = 30
    for i in range(4 * fps):
        yield car_frame(1280, 720, i / fps)


def plates_portrait():
    # Upright it's 720 x 1280; phones store it turned a quarter to the left, with "rotate 90 clockwise to show".
    fps = 30
    for i in range(4 * fps):
        yield car_frame(720, 1280, i / fps).transpose(Image.Transpose.ROTATE_90)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    encode("traffic.mp4", (960, 540), 30, traffic(), date="2026-09-29T07:10:00.000000Z")
    encode("plates.mp4", (1280, 720), 30, plates())
    # ffmpeg's display rotation is counter-clockwise: -90 is Android's "rotation-degrees" 90.
    encode("plates-portrait.mp4", (1280, 720), 30, plates_portrait(), rotation=-90)
