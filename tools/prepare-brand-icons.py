"""Derive Android/store assets from approved raster masters; never copy app source."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
IDS = ('winali', 'apk', 'focus', 'read', 'books')


def prepare():
    previews = []
    for app_id in IDS:
        project = ROOT if app_id == 'winali' else ROOT / 'apps' / app_id
        with Image.open(project / 'branding' / 'icon-3d.png') as master:
            art = master.convert('RGB').resize((512, 512), Image.Resampling.LANCZOS)
        res = project / 'app/src/main/res'
        (res / 'drawable-nodpi').mkdir(parents=True, exist_ok=True)
        art.save(res / 'drawable-nodpi/launcher_art.png', optimize=True)
        art.save(ROOT / 'docs/icons' / f'{app_id}-3d.png', optimize=True)
        previews.append((app_id, art))
    store_res = ROOT / 'app/src/main/res/drawable-nodpi'
    for app_id in (*IDS, 'deck'):
        source = ROOT / 'docs/icons' / (f'{app_id}-3d.png' if app_id != 'deck' else 'deck.png')
        with Image.open(source) as art:
            art.convert('RGB').resize((256, 256), Image.Resampling.LANCZOS).save(store_res / f'catalog_icon_{app_id}.png', optimize=True)
    with Image.open(ROOT / 'docs/icons/deck.png') as deck:
        previews.insert(0, ('deck (referencia)', deck.convert('RGB').resize((512, 512))))
    sheet = Image.new('RGB', (1440, 720), '#101525')
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 24)
    for i, (name, art) in enumerate(previews):
        x, y = (i % 3) * 480, (i // 3) * 360
        sheet.paste(art.resize((256, 256), Image.Resampling.LANCZOS), (x + 24, y + 24))
        draw.text((x + 24, y + 292), name, font=font, fill='white')
        # Preview foreground inset and two common adaptive launcher masks at 48px.
        for j, roundness in enumerate((24, 12)):
            mini = Image.new('RGB', (72, 72), '#060D29')
            mini.paste(art.resize((60, 60), Image.Resampling.LANCZOS), (6, 6))
            # Android crops its 108dp foreground to a 72dp viewport.
            mini = mini.crop((12, 12, 60, 60))
            mask = Image.new('L', (48, 48))
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, 47, 47), radius=roundness, fill=255)
            sheet.paste(mini, (x + 315 + j * 72, y + 126), mask)
    sheet.save(ROOT / 'docs/icons/family-3d-preview.png', optimize=True)
    print('Prepared 5 launcher/store icons, 6 offline store icons and adaptive previews.')


if __name__ == '__main__':
    prepare()
