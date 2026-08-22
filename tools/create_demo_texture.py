from pathlib import Path
from PIL import Image, ImageDraw

output = Path('/home/ubuntu/minecraft-lua-loader/examples/hello_lua/assets/hello_lua/textures/block/ruby_block.png')
output.parent.mkdir(parents=True, exist_ok=True)
image = Image.new('RGBA', (16, 16), (110, 20, 45, 255))
draw = ImageDraw.Draw(image)
for x in range(16):
    for y in range(16):
        if (x + y) % 5 == 0:
            draw.point((x, y), fill=(230, 65, 90, 255))
image.save(output, format='PNG')
alt_output = output.with_name('ruby_block_alt.png')
alt_image = Image.new('RGBA', (16, 16), (25, 75, 170, 255))
alt_draw = ImageDraw.Draw(alt_image)
for x in range(16):
    for y in range(16):
        if (x * 2 + y) % 5 == 0:
            alt_draw.point((x, y), fill=(80, 175, 245, 255))
alt_image.save(alt_output, format='PNG')
print(output)
print(alt_output)
