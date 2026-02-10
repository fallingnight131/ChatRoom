"""Generate app icon as PNG and ICO"""
from PIL import Image, ImageDraw, ImageFont
import struct, io

def create_icon():
    sizes = [16, 32, 48, 64, 128, 256]
    images = []
    
    for size in sizes:
        img = Image.new('RGBA', (size, size), (0,0,0,0))
        draw = ImageDraw.Draw(img)
        
        # Background circle - gradient blue
        margin = size // 10
        draw.ellipse([margin, margin, size-margin, size-margin], 
                     fill=(41, 128, 185, 255))
        
        # Inner lighter ring
        inner = margin + size//10
        draw.ellipse([inner, inner, size-inner, size-inner],
                     fill=(52, 152, 219, 255))
        
        # Chat bubble shape
        bx = size * 0.25
        by = size * 0.28
        bw = size * 0.75
        bh = size * 0.58
        draw.rounded_rectangle([bx, by, bw, bh], radius=size//8, fill=(255,255,255,240))
        
        # Three dots for "typing"
        dot_r = max(1, size // 16)
        cy = int((by + bh) / 2)
        cx = int((bx + bw) / 2)
        spacing = int(size * 0.1)
        for dx in [-spacing, 0, spacing]:
            draw.ellipse([cx+dx-dot_r, cy-dot_r, cx+dx+dot_r, cy+dot_r],
                        fill=(41, 128, 185, 255))
        
        images.append(img)
    
    # Save as ICO
    images[-1].save('app_icon.ico', format='ICO', 
                    sizes=[(s, s) for s in sizes],
                    append_images=images[:-1])
    
    # Save 256x256 as PNG for Qt resource
    images[-1].save('app_icon.png', format='PNG')
    print("Generated app_icon.ico and app_icon.png")

if __name__ == '__main__':
    create_icon()
