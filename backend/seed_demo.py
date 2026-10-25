"""Adds a few sample deals so the app can be tried before the Telegram login is set up.
Remove them later with: python seed_demo.py --clear
"""
import sys
import time

import db
from offer_parser import parse_offer

SAMPLES = [
    ("Offerzone 3.0", "GRAB : Philips Viva Induction Cooktop Black at 2,997.  https://amzn.to/4yGSmhg"),
    ("Offerzone 4.0", "Madhura's Recipe Kanda Lasun Masala (Pack of 3 x 100g) at 269.  https://amzn.to/3demo01"),
    ("DC LOOTS Offers And Deals", "72% Off : Parachute Body Lotion 400 ml @140.  https://amzn.to/4wYJWlc"),
    ("Offer Box 2.0", "Upto 92% Off On Symbol Clothing.\n\nWomen : https://link.amazon/B08UGhFq6\nMen : https://link.amazon/B0demo02"),
    ("Offerzone 2.0", "Lowest : Yogabar Ultimate Whey Protein (Chocolate, 500g) at 1399.  https://amzn.to/3demo03"),
    ("Offer Box Official", "Loot : Upto 90% Off On LAttitude By Provogue Trolley Bags Starts @999.\n999 (S) : https://fkrt.it/demo04"),
    ("Offerzone Tricks", "GRAB : Orient Electric Emergency Lanterns & LED Desk Lamps Starts at ₹99.  https://fkrt.it/demo05"),
    ("Offerzone", "BIG LOOT : BLDC Fan.\n₹50 : https://link.amazon/B02Rd4kQe\n₹100 : https://link.amazon/B0demo06"),
    ("Offer Box 2.0", "Boat Airdopes 141 TWS\nDeal Price: ₹899\nMRP: ₹4,490\nhttps://myntr.it/demo07"),
]

if __name__ == "__main__":
    if "--clear" in sys.argv:
        db.delete_offers([f"demo:{i}" for i in range(len(SAMPLES))])
        print("demo offers removed")
    else:
        now = int(time.time())
        for i, (source, text) in enumerate(SAMPLES):
            db.upsert_offer(f"demo:{i}", source, parse_offer(text), now - i * 600, None)
        print(f"added {len(SAMPLES)} demo offers")
