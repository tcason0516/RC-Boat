"""Announcer script for the Maya1 voice. Key -> line. Tags in <> are Maya1 emotion tags.

Keys are the clip names the game asks for (assets/announcer/<key>.ogg). A set of
variants is <base>_0, <base>_1, ... and Announcer picks one at random.
"""

VOICE = ("Realistic male voice in the 40s age with american accent. Deep pitch, booming gravelly "
         "timbre, very energetic, loud motorsport race announcer, fast pacing.")

OPP = {1: "Blue Thunder", 2: "Green Machine", 3: "Yellow Jacket", 4: "Purple Haze", 5: "Orange Crush"}

X = "<excited> "
LEAD_ANY = "Uh oh. Somebody's in the lead, and it ain't you!"

LINES = {
    "start_0": X + "Welcome to the lake, race fans! Boats are on the line!",
    "start_1": X + "Engines are hot and the water's like glass. Let's go racing!",
    "start_2": X + "Drivers, get ready! This one's gonna be a barn burner!",
    "start_3": "Welcome, race fans! The local ducks have been warned.",
    "start_4": "Today's forecast: sunny, with a hundred percent chance of wakes!",

    "count_3": X + "Three!",
    "count_2": X + "Two!",
    "count_1": X + "One!",
    "go_0": X + "Go, go, go!",
    "go_1": X + "Go! Go! Go! And may your batteries last!",

    "leadme_0": X + "You've taken the lead!",
    "leadme_1": X + "Look at that! You're out front!",
    "leadme_2": X + "And you power into first place!",
    "leadme_3": X + "You're in the lead! Somebody check that boat for illegal parts!",
    "leadme_4": X + "First place! Mom's putting this one on the fridge!",

    "leadlost_0": "Ohh, you've lost the lead!",
    "leadlost_1": "You've been passed for the lead!",
    "leadlost_2": "And you've lost the lead. Didn't see that coming. Oh wait, we all did.",
    "leadlost_3": "The lead is gone! Wave bye-bye!",

    "upgen_0": X + "Nice pass!",
    "upgen_1": X + "You pick up another spot!",
    "upgen_2": X + "Great move, you're moving up!",
    "upgen_3": X + "You passed him like he was parked!",

    "down_0": "Ooh, you lost a spot!",
    "down_1": "You just got passed!",
    "down_2": "You just got passed by a boat that sounds like a blender!",

    "bump_0": X + "Bump and run!",
    "bump_1": X + "Whoa, contact!",
    "bump_2": X + "They're trading paint out there!",
    "bump_3": "That's gonna void the warranty!",
    "bump_4": "Insurance rates on this lake just went up!",

    "buoy_0": "You clipped the buoy!",
    "buoy_1": "Watch those buoys!",
    "buoy_2": "That buoy had a family!",

    "wrong_0": "Wrong way! Turn it around!",
    "wrong_1": "You're going the wrong way!",
    "wrong_2": "Other direction, driver!",
    "wrong_3": "Wrong way! The finish line is the other way!",

    "off_0": "You're off the course!",
    "off_1": "Get it back on the racing line!",
    "off_2": "You're off course! Are you fishing out there?",

    "togo_8": "Eight laps to go!",
    "togo_7": "Seven laps to go!",
    "togo_6": "Six laps to go!",
    "togo_5": "Five laps to go!",
    "togo_4": "Four laps to go!",
    "togo_3": X + "Three laps to go!",
    "togo_2_0": X + "Two laps to go!",
    "togo_2_1": "Two to go, and my nerves can't take it!",
    "white_0": X + "Final lap! Final lap!",
    "white_1": X + "White flag! One lap to go!",
    "white_2": X + "Final lap! Hope you charged those batteries!",

    "front_0": X + "You're running away with this one!",
    "front_1": X + "Nobody can catch you out there!",
    "front_2": X + "What a drive! You're checking out!",
    "front_3": "You're winning by so much, the other boats filed a complaint!",
    "last_0": "You're bringing up the rear. Time to dig deep!",
    "last_1": X + "Still plenty of race left. Go get 'em!",
    "last_2": "You're so far back, you're technically in the next race!",

    "winme_0": X + "Checkered flag! You win it!",
    "winme_1": X + "You win! What a race!",
    "winme_2": X + "You win! Juice boxes for everybody!",

    "fin_2": "You bring it home in second!",
    "fin_3": "Third place. You're on the podium!",
    "fin_4": "You finish fourth.",
    "fin_5": "Fifth place finish.",
    "fin_6": "You finish sixth. Better luck next time.",
    "fin_last": "Last place! But hey, you looked great doing it!",
}

for i, n in OPP.items():
    LINES["lead_%d_0" % i] = X + n + " takes the lead!"
    LINES["winopp_%d" % i] = X + "Checkered flag! " + n + " wins it!"
LINES["lead_any"] = LEAD_ANY
LINES["lead_5_soda"] = "You're getting beat by a boat named after a soda!"

if __name__ == "__main__":
    print(len(LINES), "lines")
