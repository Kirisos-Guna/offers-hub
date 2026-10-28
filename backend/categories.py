"""Puts a deal into a shopping category from the words in its title and post.

Checked in order; the first category with a matching word wins, so more specific groups
(e.g. "smartwatch" -> Electronics) come before broader ones ("watch" -> Fashion).
"""
import re

CATEGORIES: list[tuple[str, list[str]]] = [
    ("Electronics", [
        "smartphone", "mobile", "iphone", "phone", "laptop", "macbook", "tablet!", "ipad", "tv", "television",
        "zebronics", "boat", "noise", "realme", "redmi", "oneplus", "samsung", "jbl", "sony", "pixaplay",
        "monitor", "earbuds", "earphone", "headphone", "headset", "neckband", "airdopes", "tws", "speaker",
        "soundbar", "smartwatch", "smart watch", "fitness band", "charger", "power bank", "powerbank", "cable",
        "camera", "dslr", "keyboard", "mouse", "ssd", "hard disk", "pendrive", "pen drive", "memory card",
        "router", "printer", "projector", "alexa", "echo", "fire tv", "chromecast", "gaming", "console",
        "processor", "graphics card", "ram!", "trimmer", "shaver", "hair dryer", "straightener",
        "cctv", "security camera", "smart plug", "extension board", "ups", "drone", "earpods", "airpods",
    ]),
    ("Car & Bike", [
        "helmet", "car", "bike", "motorcycle", "scooter", "scooty", "tyre", "tire", "dashcam", "dash cam",
        "seat cover", "engine oil", "car wash", "car perfume", "riding gloves", "number plate",
    ]),
    ("Home & Kitchen", [
        "cooker", "pressure cooker", "mixer", "grinder", "juicer", "kitchen", "cookware", "kadai", "tawa",
        "pan", "bottle", "flask", "lunch box", "container", "bedsheet", "comforter", "pillow", "mattress",
        "curtain", "towel", "lamp", "bulb", "led", "lights", "fan", "cooler", "geyser", "water heater",
        "heater", "iron", "vacuum", "refrigerator", "fridge", "washing machine", "air conditioner", " ac ",
        "microwave", "oven", "chimney", "induction", "air fryer", "kettle", "water purifier", "furniture",
        "sofa", "chair", "table", "wardrobe", "storage", "organiser", "organizer", "decor", "lantern",
        "inverter", "battery", "tool", "drill", "appliance", "home appliances", "air purifier", "purifier",
        "dinner set", "glass", "mug", "plate", "dustbin", "mop", "cleaner", "detergent",
    ]),
    ("Fashion", [
        "shirt", "t-shirt", "tshirt", "jeans", "trouser", "shoes", "sneakers", "sandals", "slippers", "footwear",
        "watch", "watches", "saree", "kurta", "kurti", "dress", "lehenga", "jacket", "hoodie", "sweatshirt",
        "clothing", "innerwear", "bra", "socks", "handbag", "bag", "backpack", "wallet", "belt", "sunglasses",
        "jewellery", "jewelry", "earrings", "ring", "trolley", "luggage", "suitcase", "puma", "adidas", "nike",
        "levis", "allen solly", "u.s. polo", "red tape", "bata", "asics", "briefs", "trunks", "vest", "wrogn",
    ]),
    ("Beauty & Health", [
        "shampoo", "conditioner", "lotion", "cream", "serum", "face wash", "facewash", "sunscreen", "lipstick",
        "makeup", "perfume", "deo", "deodorant", "body spray", "soap", "body wash", "toothpaste", "toothbrush",
        "hair oil", "protein", "whey", "vitamin", "tablets", "capsules", "ashwagandha", "supplement",
        "sanitary", "diaper", "health", "medicine", "beauty", "skin", "nivea", "himalaya", "mamaearth", "lakme",
        "massager", "face mask", "hair color", "hair colour", "razor", "grooming", "kajal", "nail",
        "lip balm", "hair care", "vaseline", "manforce", "condom",
    ]),
    ("Grocery", [
        "masala", "spices", "atta", "rice", "dal", "oil", "ghee", "sugar", "salt", "tea", "coffee", "chocolate",
        "biscuit", "cookies", "snacks", "namkeen", "chips", "dry fruits", "dryfruits", "almonds", "cashew",
        "kaju", "honey", "jam", "noodles", "pasta", "juice", "grocery", "kirana", "haldiram", "maggi",
        "raisins", "kishmish", "dates", "walnut", "pista", "makhana", "oats", "cereal", "flour",
    ]),
    ("Kids & Toys", ["toy", "toys", "kids", "baby", "lego", "puzzle", "school bag", "crayon", "stationery",
                     "pen", "pencil", "notebook"]),
    ("Sports & Fitness", ["cycle", "bicycle", "dumbbell", "yoga", "gym", "fitness", "cricket", "football",
                          "badminton", "treadmill", "sports"]),
    ("Cards & Offers", ["credit card", "debit card", "cashback", "coupon", "gift card", "voucher", "recharge",
                        "bill payment", "upi", "lifetime free", "referral"]),
]

def _word(w: str) -> str:
    """Matches the word and its plural (fans, deodorants, watches); "word!" means no plural
    ("tablet" the device, but "tablets" are medicine)."""
    w = w.strip()
    return re.escape(w[:-1]) if w.endswith("!") else re.escape(w) + r"(?:e?s)?"


_PATTERNS = [
    (name, re.compile(r"(?<![a-z])(?:" + "|".join(_word(w) for w in words) + r")(?![a-z])"))
    for name, words in CATEGORIES
]


def categorize(title: str, text: str = "") -> str:
    """The deal's category, judged from its title first and then the rest of the post.
    "Cards & Offers" counts only in the title: most posts mention a bank-card discount."""
    for haystack, from_title in ((title.lower(), True), (text.lower(), False)):
        for name, pattern in _PATTERNS:
            if (from_title or name != "Cards & Offers") and pattern.search(haystack):
                return name
    return "Other"
