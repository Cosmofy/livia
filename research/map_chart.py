import matplotlib.pyplot as plt
import cartopy.crs as ccrs
import cartopy.feature as cfeature
import matplotlib as mpl
from matplotlib.font_manager import FontProperties
import matplotlib.lines as mlines

servers = {
    "Singapore": (1.3521, 103.8198, "bottom"), 
    "Texas, USA": (32.7767, -96.7970, "bottom"),
    "London, UK": (51.5072, -0.1276, "top")
}

test_locations = {
    "Pennsylvania, USA": (40.2732, -76.8867, "top"),
    "Milan, Italy": (45.468001522352246, 9.178759132719067, "bottom"),
    "Cape Town, South Africa": (-33.9221, 18.4231, "bottom"),
    "São Paulo, Brazil": (-23.563791606228214, -46.62969711528476, "top"),
    "Abu Dhabi, UAE": (24.498812785281913, 54.60503722806657, "bottom"),
    "Tokyo, Japan": (35.6764, 139.6500, "top"),
    # "Hyderabad, India": (17.415556891232864, 78.48699275644994, "bottom"), # no longer a testing location
    "Sydney, Australia": (-33.869472886714874, 151.21206729482654, "bottom"),
}

# Setup figure
fig = plt.figure(figsize=(10,5))
ax = plt.axes(projection=ccrs.PlateCarree())
legend_font = FontProperties(fname="Inconsolata-Medium.ttf", size=15)  # put the correct filename here
adjust = 4
# Black & white map style
ax.add_feature(cfeature.LAND, facecolor="#e8e8e8")
ax.add_feature(cfeature.OCEAN, facecolor="white")
ax.add_feature(cfeature.BORDERS, edgecolor="white", linewidth=0.25)
ax.add_feature(cfeature.COASTLINE, edgecolor="white", linewidth=0.25)


# Plot servers
for city, (lat, lon, position) in servers.items():
    op, pos = None, None
    if position == "top": op, pos = "bottom", adjust
    else: op, pos = "top", -adjust

    ax.plot(lon, 
            lat, 
            "o", 
            color="darkblue", 
            markersize=6, 
            transform=ccrs.PlateCarree()
    )
    ax.text(lon, 
            lat+pos, 
            city, 
            transform=ccrs.PlateCarree(),
            fontproperties=legend_font,
            ha="center",
            va=op, 
            color="darkblue", 
    )

# Plot testing locations
for city, (lat, lon, position) in test_locations.items():
    op, pos = None, None
    if position == "top": op, pos = "bottom", adjust
    else: op, pos = "top", -adjust
    ax.plot(lon, 
            lat, 
            "o", 
            color="darkred", 
            markersize=6, 
            transform=ccrs.PlateCarree()
    )
    ax.text(lon, 
            lat+pos, 
            city, 
            transform=ccrs.PlateCarree(),
            fontproperties=legend_font, 
            ha="center",
            va=op, 
            color="darkred", 
    )



# Define custom legend markers
server_marker = mlines.Line2D([], [], color="darkblue", marker="o", linestyle="None",
                              markersize=6, label="Servers")
test_marker   = mlines.Line2D([], [], color="darkred", marker="o", linestyle="None",
                              markersize=6, label="Test Locations")

# Add legend to plot
ax.legend(handles=[server_marker, test_marker],
          loc="lower left",      # position (try "upper right", etc.)
        #   fontsize=size,
          frameon=True,         # remove legend box border
          prop=legend_font)  # match map style

# Set extent (world)
ax.set_global()
ax.axis("off")

plt.savefig("world_map.pdf", bbox_inches="tight")  # vector for LaTeX
plt.savefig("world_map.png", dpi=300, bbox_inches="tight", pad_inches=0)  # raster
# plt.show()

