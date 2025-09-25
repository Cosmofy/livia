#!/usr/bin/env python3
"""
Cosmofy GraphQL Performance Testing Script
Usage: python test.py
"""

import requests
import time
import json
import subprocess
import threading
from datetime import datetime
from zoneinfo import ZoneInfo

# =====================================
# SETUP AND CONFIG
# =====================================

# Get test location from user
location = input("Enter test location (e.g., 'US-West', 'Germany', 'Japan'): ")
print(f"\nStarting performance tests from {location}")
print("=" * 60)

# Store all results
results = []

# Helper function to save results
def save_result(test_name, response_time_seconds, response_size_bytes, status_code, notes=""):
    result = {
        'location': location,
        'test': test_name,
        'response_time_ms': round(response_time_seconds * 1000, 2),
        'response_size_bytes': response_size_bytes,
        'status_code': status_code,
        'timestamp': datetime.now().isoformat(),
        'notes': notes
    }
    results.append(result)
    print(f"✓ {test_name}: {result['response_time_ms']}ms, {response_size_bytes} bytes")

# =====================================
# TEST 1-4: PING TESTS TO ALL SERVERS
# =====================================

print("\nTest 1-4: Infrastructure Ping Tests")
print("-" * 40)

time.sleep(1)
# Test 1: Ping prod1 (Oracle UK)
try:
    result = subprocess.run(['ping', '-c', '3', 'prod1.livia.arryan.xyz'],
                          capture_output=True, text=True, timeout=10)
    if result.returncode == 0:
        lines = result.stdout.split('\n')
        for line in lines:
            if 'avg' in line or 'Average' in line:
                avg_time = float(line.split('/')[4]) if '/' in line else 0
                save_result("1. Ping prod1 (Oracle UK)", avg_time/1000, 0, 200, "ping prod1 success")
                break
    else:
        save_result("1. Ping prod1 (Oracle UK)", 0, 0, 999, "ping failed")
except:
    save_result("1. Ping prod1 (Oracle UK)", 0, 0, 999, "ping error")

time.sleep(1)
# Test 2: Ping prod2 (Google US)
try:
    result = subprocess.run(['ping', '-c', '3', 'prod2.livia.arryan.xyz'],
                          capture_output=True, text=True, timeout=10)
    if result.returncode == 0:
        lines = result.stdout.split('\n')
        for line in lines:
            if 'avg' in line or 'Average' in line:
                avg_time = float(line.split('/')[4]) if '/' in line else 0
                save_result("2. Ping prod2 (Google US)", avg_time/1000, 0, 200, "ping prod2 success")
                break
    else:
        save_result("2. Ping prod2 (Google US)", 0, 0, 999, "ping failed")
except:
    save_result("2. Ping prod2 (Google US)", 0, 0, 999, "ping error")

time.sleep(1)
# Test 3: Ping prod3 (AWS Singapore)
try:
    result = subprocess.run(['ping', '-c', '3', 'prod3.livia.arryan.xyz'],
                          capture_output=True, text=True, timeout=10)
    if result.returncode == 0:
        lines = result.stdout.split('\n')
        for line in lines:
            if 'avg' in line or 'Average' in line:
                avg_time = float(line.split('/')[4]) if '/' in line else 0
                save_result("3. Ping prod3 (AWS Singapore)", avg_time/1000, 0, 200, "ping prod3 success")
                break
    else:
        save_result("3. Ping prod3 (AWS Singapore)", 0, 0, 999, "ping failed")
except:
    save_result("3. Ping prod3 (AWS Singapore)", 0, 0, 999, "ping error")

time.sleep(5)
# Test 4: Ping default (Route53)
try:
    result = subprocess.run(['ping', '-c', '3', 'livia.arryan.xyz'],
                          capture_output=True, text=True, timeout=10)
    if result.returncode == 0:
        lines = result.stdout.split('\n')
        for line in lines:
            if 'avg' in line or 'Average' in line:
                avg_time = float(line.split('/')[4]) if '/' in line else 0
                save_result("4. Ping default (Route53)", avg_time/1000, 0, 200, "ping route53 success")
                break
    else:
        save_result("4. Ping default (Route53)", 0, 0, 999, "ping failed")
except:
    save_result("4. Ping default (Route53)", 0, 0, 999, "ping error")

# =====================================
# TEST 5-10: APOD TESTS
# =====================================

print("\nTest 5-10: APOD Performance Tests")
print("-" * 40)
today = datetime.now(ZoneInfo("US/Mountain")).strftime("%Y-%m-%d")
time.sleep(2)
# Test 5: NASA APOD today direct
try:
    start_time = time.time()
    response = requests.get(f"https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&date={today}", timeout=30)
    end_time = time.time()
    save_result("5. NASA APOD Today Direct", end_time - start_time, len(response.content), response.status_code, "ping nasa apod success")
except Exception as e:
    save_result("5. NASA APOD Today Direct", 0, 0, 999, f"error: {str(e)}")

time.sleep(2)
# Test 6: NASA APOD Jan 1 2000 direct
try:
    start_time = time.time()
    response = requests.get("https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&date=2000-01-01", timeout=30)
    end_time = time.time()
    save_result("6. NASA APOD Jan 1 2000 Direct", end_time - start_time, len(response.content), response.status_code, "ping nasa apod historical success")
except Exception as e:
    save_result("6. NASA APOD Jan 1 2000 Direct", 0, 0, 999, f"error: {str(e)}")

time.sleep(2)
# Test 7: GraphQL APOD today non-cached
# print("NOTE: Make sure Redis cache is CLEARED for non-cached tests!")
apod_query = {
    "query": """
    query GetAPOD($date: String) {
        picture(date: $date) {
            title
            explanation {
                original
                summarized
                kids
            }
            media
            date
        }
    }
    """,
    "variables": {"date": today}
}

try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=apod_query,
                           headers={"Content-Type": "application/json"},
                           timeout=130)
    end_time = time.time()
    save_result("7. GraphQL APOD Today Non-Cached", end_time - start_time, len(response.content), response.status_code, "ping route53 apod success")
except Exception as e:
    save_result("7. GraphQL APOD Today Non-Cached", 0, 0, 999, f"error: {str(e)}")

time.sleep(2)
# Test 8: GraphQL APOD today cached (run same query again)
try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=apod_query,
                           headers={"Content-Type": "application/json"},
                           timeout=130)
    end_time = time.time()
    save_result("8. GraphQL APOD Today Cached", end_time - start_time, len(response.content), response.status_code, "ping route53 apod success")
except Exception as e:
    save_result("8. GraphQL APOD Today Cached", 0, 0, 999, f"error: {str(e)}")

time.sleep(2)
# Test 9: GraphQL APOD Jan 1 2000 non-cached
apod_old_query = {
    "query": """
    query GetAPOD($date: String) {
        picture(date: $date) {
            title
            explanation {
                original
                summarized
                kids
            }
            media
            date
        }
    }
    """,
    "variables": {"date": "2000-01-01"}
}

try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=apod_old_query,
                           headers={"Content-Type": "application/json"},
                           timeout=160)
    end_time = time.time()
    save_result("9. GraphQL APOD Jan 1 2000 Non-Cached", end_time - start_time, len(response.content), response.status_code, "ping route53 apod historical success")
except Exception as e:
    save_result("9. GraphQL APOD Jan 1 2000 Non-Cached", 0, 0, 999, f"error: {str(e)}")

time.sleep(2)
# Test 10: GraphQL APOD Jan 1 2000 cached
try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=apod_old_query,
                           headers={"Content-Type": "application/json"},
                           timeout=130)
    end_time = time.time()
    save_result("10. GraphQL APOD Jan 1 2000 Cached", end_time - start_time, len(response.content), response.status_code, "ping route53 apod success")
except Exception as e:
    save_result("10. GraphQL APOD Jan 1 2000 Cached", 0, 0, 999, f"error: {str(e)}")

# =====================================
# TEST 11-14: EVENTS TESTS
# =====================================

print("\nTest 11-14: Events Performance Tests")
print("-" * 40)
time.sleep(2)
# Test 11: NASA EONET direct
try:
    start_time = time.time()
    response = requests.get("https://eonet.gsfc.nasa.gov/api/v3/events?start=2025-09-11&end=2030-09-24&status=all", timeout=30)
    end_time = time.time()
    save_result("11. NASA EONET Direct", end_time - start_time, len(response.content), response.status_code, "ping nasa eonet success")
except Exception as e:
    save_result("11. NASA EONET Direct", 0, 0, 999, f"error: {str(e)}")

time.sleep(2)
# Test 12: GraphQL Events non-cached
# print("NOTE: Clear Redis cache before this test for non-cached results!")
events_query = {
    "query": """
    query GetEvents {
        events {
            id
            title
            categories {
                title
            }
            geometry {
                coordinates
                date
            }
        }
    }
    """
}

try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=events_query,
                           headers={"Content-Type": "application/json"},
                           timeout=130)
    end_time = time.time()
    save_result("12. GraphQL Events Non-Cached", end_time - start_time, len(response.content), response.status_code, "ping route53 eonet success")
except Exception as e:
    save_result("12. GraphQL Events Non-Cached", 0, 0, 999, f"error: {str(e)}")

time.sleep(2)
# Test 13: GraphQL Events cached
try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=events_query,
                           headers={"Content-Type": "application/json"},
                           timeout=130)
    end_time = time.time()
    save_result("13. GraphQL Events Cached", end_time - start_time, len(response.content), response.status_code, "ping route53 eonet success")
except Exception as e:
    save_result("13. GraphQL Events Cached", 0, 0, 999, f"error: {str(e)}")

# =====================================
# TEST 14-17: JPL PLANETS TESTS
# =====================================

print("\n Test 14-17: JPL Planets Performance Tests")
print("-" * 40)

planet_ids = ['199', '299', '399', '499', '599', '699', '799', '899']  # Mercury to Neptune

time.sleep(2)
# Test 14: JPL Planets 1-8 single thread
print("Testing JPL single threaded...")
start_time = time.time()
total_size = 0
for planet_id in planet_ids:
    try:
        response = requests.get(f"https://ssd-api.jpl.nasa.gov/horizons.cgi?COMMAND={planet_id}&MAKE_EPHEM=YES", timeout=30)
        total_size += len(response.content)
    except:
        pass
end_time = time.time()
save_result("14. JPL Planets 1-8 Single Thread", end_time - start_time, total_size, 200, "ping nasa jpl single success")

time.sleep(2)
# Test 15: JPL Planets 1-8 multi thread (parallel)
print("Testing JPL multi threaded...")
jpl_results = []

def fetch_jpl_planet(planet_id, results_list):
    try:
        response = requests.get(f"https://ssd-api.jpl.nasa.gov/horizons.cgi?COMMAND={planet_id}&MAKE_EPHEM=YES", timeout=30)
        results_list.append(len(response.content))
    except:
        results_list.append(0)

start_time = time.time()
threads = []
for planet_id in planet_ids:
    thread = threading.Thread(target=fetch_jpl_planet, args=(planet_id, jpl_results))
    threads.append(thread)
    thread.start()

for thread in threads:
    thread.join()

end_time = time.time()
total_parallel_size = sum(jpl_results)
save_result("15. JPL Planets 1-8 Multi Thread", end_time - start_time, total_parallel_size, 200, "ping nasa jpl multi success")

time.sleep(2)
# Test 16: GraphQL Planets non-cached
# print("NOTE: Clear Redis cache before this test for non-cached results!")
planets_query = {
    "query": """
    query GetPlanets {
        planets {
            name
            temperature
            mass
            radiusEquatorial
            gravityEquatorial
            description
            atmosphere {
                name
                percentage
            }
        }
    }
    """
}

try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=planets_query,
                           headers={"Content-Type": "application/json"},
                           timeout=160)  # Longer timeout for AI processing
    end_time = time.time()
    save_result("16. GraphQL Planets Non-Cached", end_time - start_time, len(response.content), response.status_code, "ping route53 jpl success")
except Exception as e:
    save_result("16. GraphQL Planets Non-Cached", 0, 0, 999, f"error: {str(e)}")

time.sleep(5)
# Test 17: GraphQL Planets cached
try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=planets_query,
                           headers={"Content-Type": "application/json"},
                           timeout=130)
    end_time = time.time()
    save_result("17. GraphQL Planets Cached", end_time - start_time, len(response.content), response.status_code, "ping route53 jpl success")
except Exception as e:
    save_result("17. GraphQL Planets Cached", 0, 0, 999, f"error: {str(e)}")

# =====================================
# TEST 18: ARTICLES TEST
# =====================================

print("\nTest 18: Articles Performance Test")
print("-" * 40)

time.sleep(2)
# Test 18: GraphQL Articles (always cached)
articles_query = {
    "query": """
    query GetArticles {
        articles {
            title
            subtitle
            url
            authors {
                name
                title
            }
            source
        }
    }
    """
}

try:
    start_time = time.time()
    response = requests.post("https://livia.arryan.xyz/graphql",
                           json=articles_query,
                           headers={"Content-Type": "application/json"},
                           timeout=130)
    end_time = time.time()
    save_result("18. GraphQL Articles", end_time - start_time, len(response.content), response.status_code, "ping route53 articles success")
except Exception as e:
    save_result("18. GraphQL Articles", 0, 0, 999, f"error: {str(e)}")

# =====================================
# SAVE RESULTS AND SUMMARY
# =====================================
time.sleep(1)
print("\nSaving Results...")
print("-" * 40)

# Save to JSON file
filename = f"performance_results_{location.replace(' ', '_').replace('-', '_')}.json"
with open(filename, 'w') as f:
    json.dump(results, f, indent=2)

print(f"Results saved to {filename}")

# Print summary
print(f"\nTest Summary for {location}:")
print("=" * 60)
for result in results:
    status = "✓" if result['status_code'] == 200 else "✗"
    print(f"{status} {result['test']}: {result['response_time_ms']}ms")

print(f"\nAll {len(results)} tests completed!")
time.sleep(1)
print(f"Results file: {filename}")


"""
sudo docker exec -it mongo mongosh --eval 'db.getMongo().getDBNames().forEach(function(n){ if(!["admin","config","local"].includes(n)){ db.getSiblingDB(n).dropDatabase(); } })'
sudo docker exec -it redis redis-cli FLUSHALL
"""
