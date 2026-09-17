# Private Vault 1.4.3

- Adds an offline transparent RuPay logo, with light lettering on dark cards.
- Enter RuPay in the editable Network field after entering the card number. Saved network names take precedence on the card face.
- NFC imports preserve RuPay when the parser identifies its chip application, instead of overriding it with a number-prefix guess. Number-only RuPay detection is not added without a reliable issuer-range source. Physical RuPay NFC compatibility remains untested.

Logo source: https://commons.wikimedia.org/wiki/File:RuPay.svg
Artwork credited there to National Payments Corporation of India, sourced from its brand guidelines, and marked PD-textlogo. RuPay remains an NPCI trademark. Converted paths to Android vectors, added clear space, and provided a white-lettering variant.
