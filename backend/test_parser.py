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


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
