"""Run: python -m pytest test_parser.py  (or just: python test_parser.py)"""
from offer_parser import parse_offer


def test_grab_with_price():
    o = parse_offer("GRAB : Philips Viva Induction Cooktop Black at 2,997.  https://amzn.to/4yGSmhg *₹500 off with bank card")
    assert o["tag"] == "GRAB"
    assert o["title"] == "Philips Viva Induction Cooktop Black"
    assert o["price"] == 2997
    assert o["store"] == "Amazon"
    assert o["url"] == "https://amzn.to/4yGSmhg"


def test_discount_tag_and_at_sign_price():
    o = parse_offer("72% Off : Parachute Body Lotion 400 ml @140.  https://amzn.to/4wYJWlc")
    assert o["discount"] == 72
    assert o["price"] == 140
    assert o["title"] == "Parachute Body Lotion 400 ml"


def test_multiple_labelled_links():
    o = parse_offer("Upto 92% Off On Symbol Clothing.\n\nWomen : https://link.amazon/B08UGhFq6\nMen : https://link.amazon/B0X1")
    assert o["discount"] == 92
    assert [l["label"] for l in o["links"]] == ["Women", "Men"]
    assert o["title"] == "Upto 92% Off On Symbol Clothing"


def test_price_labels_and_flipkart():
    o = parse_offer("BIG LOOT : BLDC Fan.\n₹50 : https://fkrt.it/abc\n₹100 : https://fkrt.it/def")
    assert o["tag"] == "BIG LOOT"
    assert o["store"] == "Flipkart"
    assert [l["label"] for l in o["links"]] == ["₹50", "₹100"]


def test_mrp_gives_discount():
    o = parse_offer("Boat Airdopes 141\nDeal Price: ₹899\nMRP: ₹4,490\nhttps://amzn.to/xyz")
    assert o["price"] == 899
    assert o["mrp"] == 4490
    assert o["discount"] == 80


def test_no_link_is_not_an_offer():
    assert parse_offer("Good morning everyone! Big sale starts tonight") is None
    assert parse_offer("Join our backup channel https://t.me/+abc") is None


def test_hidden_link_from_entities():
    o = parse_offer("Loot : Orient LED Desk Lamp Starts at ₹99", ["https://www.flipkart.com/x"])
    assert o["store"] == "Flipkart"
    assert o["price"] == 99
    assert o["title"] == "Orient LED Desk Lamp"


def test_price_only_posts_get_weak_title():
    o = parse_offer("199 https://link.amazon/B0e5CD37D")
    assert o["price"] == 199 and o["weak_title"] and o["title"] == "Amazon deal"
    o = parse_offer("Loot 4891 https://link.amazon/B0eFrfPLb\n\nSelected Location")
    assert o["price"] == 4891 and o["weak_title"]
    o = parse_offer("Loot from ₹174.\n\nhttps://amzn.to/4yDn7Uc")
    assert o["price"] == 174 and o["weak_title"]
    o = parse_offer("Loot 3499 + Bank Offer. \n\nhttps://amzn.to/4zds6w8")
    assert o["price"] == 3499 and o["weak_title"]


def test_flat_rupee_off_is_not_price():
    o = parse_offer("Flat ₹8000 Off On Ray-Ban Meta (Gen 2) AI Glasses.\n\nMatte Black : https://amzn.to/4snPBQn")
    assert o["title"] == "Flat ₹8000 Off On Ray-Ban Meta (Gen 2) AI Glasses"
    assert o["price"] is None and not o["weak_title"]


def test_invisible_characters_dont_hide_the_price():
    o = parse_offer("Godrej 600L Side by Side Refrigerator at​49,990*\n\nhttps://amzn.to/4zY1QWn\n\n"
                    "Apply ₹4000 Coupon + ₹16,000 Off With HDFC CC")
    assert o["title"] == "Godrej 600L Side by Side Refrigerator"
    assert o["price"] == 49990
    assert parse_offer("Axe Deos Starts at ‎75. https://link.amazon/B0x")["price"] == 75


def test_sizes_at_line_start_are_not_prices():
    o = parse_offer("Upto 60% Off On Water Heater\n\nUpto 5L Geyser : https://amzn.to/a\n"
                    "5 - 10L Geyser : https://amzn.to/b\n10 - 20L Geyser : https://amzn.to/c")
    assert o["price"] is None and o["discount"] == 60
    assert parse_offer("336 https://amzn.to/x\n299 https://amzn.to/y")["price"] == 336


def test_categories():
    from categories import categorize
    assert parse_offer("GRAB : Philips Viva Induction Cooktop at 2,997. https://amzn.to/a")["category"] == "Home & Kitchen"
    assert categorize("boAt Airdopes 141 TWS Earbuds") == "Electronics"
    assert categorize("Fastrack Smartwatch") == "Electronics"
    assert categorize("Timex Analog Watch for Women") == "Fashion"
    assert categorize("Parachute Body Lotion 400 ml") == "Beauty & Health"
    assert categorize("Haldiram's Premium Kaju Katli Gift Pack") == "Grocery"
    assert categorize("Apply Lifetime Free Scapia Card") == "Cards & Offers"
    assert categorize("Tablets 30 pcs") == "Beauty & Health"  # not "table"
    assert categorize("Steelbird Air 7 Wings SBA-20 Full-Face Helmet",
                      "Steelbird helmet\n10% off with HDFC credit card") == "Car & Bike"
    assert categorize("Mystery box", "Extra 10% off with credit card") == "Other"  # card talk in the post


def test_expired_edits():
    assert parse_offer("Edit - Over Now ❌\n\nBoat Earbuds at 899 https://amzn.to/a")["expired"]
    assert parse_offer("Loot : Fan @999 (Sold Out) https://fkrt.it/x")["expired"]
    assert not parse_offer("GRAB : Fan at 999 https://fkrt.it/x")["expired"]


def test_rs_inside_a_word_is_not_a_price():
    assert parse_offer("Pack of Stickers 100 pcs at 299 https://amzn.to/a")["price"] == 299
    assert parse_offer("Boat Trimmers 3 in 1 at 899 https://amzn.to/a")["price"] == 899
    assert parse_offer("Oil Rs.140 https://amzn.to/a")["price"] == 140
    assert parse_offer("Oil Rs 140 https://amzn.to/a")["price"] == 140


def test_amounts_off_are_not_prices():
    assert parse_offer("Parachute Oil Combo\nhttps://amzn.to/a\nApply ₹200 Coupon")["price"] is None
    assert parse_offer("Lifetime Free Card\nGet ₹500 Cashback https://amzn.to/a")["price"] is None
    assert parse_offer("Earbuds\nSave ₹300 https://amzn.to/a")["price"] is None
    assert parse_offer("Fridge at 49,990\nApply ₹4000 Coupon https://amzn.to/a")["price"] == 49990


def test_hundred_percent_off():
    assert parse_offer("100% Off : Free Sample Pack https://amzn.to/a")["discount"] == 100


def test_edit_notice_is_not_the_title():
    o = parse_offer("Edit - Over Now ❌\n\nBoat Earbuds at 899 https://amzn.to/a")
    assert o["title"] == "Boat Earbuds" and o["price"] == 899 and o["expired"]


def test_broken_url_does_not_crash():
    o = parse_offer("Deal at 99 https://[broken/x https://amzn.to/a")
    assert [l["url"] for l in o["links"]] == ["https://amzn.to/a"]


def test_named_product_is_not_weak():
    assert not parse_offer("Yellow Hat at 449.\n\nhttps://amzn.to/4BmgyrR")["weak_title"]


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
