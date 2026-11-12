# Heritage colour references

The additional palettes interpret documented special-edition keyboards. They keep Bold Keys' existing key geometry and behaviour; they do not reproduce a device, branding, logo or official colour specification. Hex values are our design choices, not manufacturer Pantone values or exact factory measurements. Reference photographs are research only and are not bundled or redistributed with the app.

| Bold Keys choice | Historical inspiration | Design translation |
|---|---|---|
| Red | BlackBerry KEY2 Red Edition (2019), described by TCL Communication as a limited edition with a deep-red Series 7 aluminium frame and **black keys** | Neutral dark keycaps, white legends, crimson body and row separators, red trackpad ring |
| Black & Gold | Limited black-and-gold BlackBerry Passport (2015), 50 numbered units | Neutral black keycaps, warm off-white legends, champagne-gold frets and ring; not gold keycaps |
| White & Gold | White-and-gold BlackBerry Q10 Special Edition (2013) | Warm ivory keycaps, charcoal legends, gold frame and frets; decorative gold does not replace readable dark text |

## Sources

- TCL Communication's release, April 19, 2019: [BlackBerry KEY2 Red Edition Now Available in the United States](https://www.prnewswire.com/news-releases/blackberry-key2-red-edition-now-available-in-the-united-states-300835026.html).
- Contemporary photographs of the numbered Passport, January 24, 2015: [Here's what the limited edition Black & Gold BlackBerry Passport looks like](https://mobilesyrup.com/2015/01/24/heres-what-the-limited-edition-black-gold-blackberry-passport-looks-like/).
- BlackBerry press-release mirror, August 19, 2013: [BlackBerry launches the Gold and White BlackBerry Q10 Special Edition smartphone](https://www.dubaiprnetwork.com/technology-news/79212-blackberry-launches-the-gold-and-white-blackberry-q10-special-edition-smartphone).

## Accessibility and compatibility

Existing Black and White palette values remain unchanged, as do the persisted `dark`, `light` and `auto` values. New stable IDs are `red`, `black_gold` and `white_gold`; an unknown value safely falls back to Black. Match phone still selects the original Black or White palette based on system appearance. Explicit colours never change with system appearance.

The same palette is used by keys, suggestions, pickers and trackpad trim. New accent selection backgrounds use dark red or dark gold behind white text. Labels accompany every colour choice and wrap on narrow screens; selected state is exposed by the standard Material chip. Backlight settings are not overwritten by changing colour, and light-key palettes remain unlit as before.

This project is independent and is not affiliated with or endorsed by BlackBerry.
