// Continent map for the factions in kingdom-behaviour.txt. The Hearthbound hold the southern coast, the Undead's
// Blightmarsh sits in the middle, the Elementals hold the west just above the Hearthbound, the Kobolds the north-west,
// the Orcs the north and the Goblins the east. Two presets: every faction starting with only its capital and the
// rest of the land in ruins (the default), or the settled start where each faction owns its home nodes.
(function (root, factory) {
  const s = factory();
  if (typeof module === 'object' && module.exports) module.exports = s;
  else { root.SCENARIOS = root.SCENARIOS || {}; root.SCENARIOS.continent = s.ruins; root.SCENARIOS.continentSettled = s.settled; root.SCENARIOS.continentDwarves = s.dwarves; }
})(typeof self !== 'undefined' ? self : this, function () {
  const start = { food: 20, wealth: 1000, res: 200 };
  const kingdoms = [
    { id: 'hb', name: 'Hearthbound Kingdom', color: '#3F6FC4', growth: 'normal', ai: 'hearthbound', player: true },
    { id: 'go', name: 'Goblins', color: '#7E9A26', growth: 'fast', ai: 'goblin', goblinRule: true },
    { id: 'or', name: 'Orcs', color: '#B0482F', growth: 'normal', ai: 'orc', strength: 1.25, upkeepMult: 1.5 },
    { id: 'ko', name: 'Kobolds', color: '#C99A2E', growth: 'slow', ai: 'kobold' },
    { id: 'un', name: 'Undead', color: '#7A5FA0', growth: 'normal', ai: 'undead', raiseDead: 0.25 },
    { id: 'el', name: 'Elementals', color: '#2A9BA8', growth: 'normal', ai: 'elemental', homeDefStrength: 1.5 },
  ].map((k) => Object.assign({}, start, k));

  // Default stats per node type for a faction's starting land: [tier, garrison, defense]
  const D = { capital: [5, 30, 10], town: [2, 10, 2], castle: [1, 15, 6], production: [2, 5, 1], food: [1, 5, 1], outpost: [1, 5, 3] };
  const node = (id, name, type, owner, x, y, over) => {
    const [tier, garrison, defense] = D[type];
    return Object.assign({ id, name, type, tier, owner, home: owner, garrison, defense, morale: 10, devastation: 0, x, y }, over || {});
  };
  const nodes = [
    // Kobolds: the peaks of the north-west
    node('ko_cap', 'Vyrnhallow', 'capital', 'ko', 150, 110),
    node('ko_town', 'Scalehollow', 'town', 'ko', 340, 80),
    node('ko_mine', 'Hoardvein Mine', 'production', 'ko', 110, 280),
    node('ko_castle', 'Emberward Hold', 'castle', 'ko', 310, 240),
    node('ko_farm', 'Mushroom Terraces', 'food', 'ko', 490, 170),
    node('ko_tower', 'Peak Sentinel', 'outpost', 'ko', 440, 330),
    // Orcs: the steppe to the north
    node('or_cap', 'Khar Dumak', 'capital', 'or', 1080, 90),
    node('or_town', 'Bloodmane Camp', 'town', 'or', 890, 90),
    node('or_mine', 'Steppe Quarry', 'production', 'or', 1270, 110),
    node('or_castle', 'Drumhold', 'castle', 'or', 1070, 240),
    node('or_farm', 'Grazing Plains', 'food', 'or', 860, 210),
    node('or_tower', 'Steppewatch', 'outpost', 'or', 1240, 280),
    // Goblins: the Goblin Wilds to the east
    node('go_cap', 'Gristlehold', 'capital', 'go', 1500, 520),
    node('go_town', 'Snagwarren', 'town', 'go', 1480, 350),
    node('go_mine', 'Scrapheap Dig', 'production', 'go', 1500, 700),
    node('go_castle', 'Rustfang Hold', 'castle', 'go', 1330, 520),
    node('go_farm', 'Grub Pits', 'food', 'go', 1330, 700),
    node('go_tower', 'Lookout Crag', 'outpost', 'go', 1350, 370),
    // Elementals: the west, just above the Hearthbound
    node('el_cap', 'The Shattered Spire', 'capital', 'el', 110, 640),
    node('el_town', 'Riftmere', 'town', 'el', 130, 470),
    node('el_mine', 'Crystal Seam', 'production', 'el', 120, 820),
    node('el_castle', 'Stormwall Hold', 'castle', 'el', 280, 640),
    node('el_farm', 'Thawed Gardens', 'food', 'el', 290, 480),
    node('el_tower', 'Frostreach Watch', 'outpost', 'el', 290, 800),
    // Undead: the Blightmarsh in the centre of the continent
    node('un_cap', 'Morvane', 'capital', 'un', 800, 480),
    node('un_town', 'Gravewater', 'town', 'un', 640, 430),
    node('un_mine', 'Bone Quarry', 'production', 'un', 960, 430),
    node('un_castle', 'Barrow Keep', 'castle', 'un', 800, 630),
    node('un_farm', 'Lily Bog', 'food', 'un', 650, 590),
    node('un_tower', 'Marsh Lantern', 'outpost', 'un', 800, 330),
    // Hearthbound: the southern coast
    node('hb_cap', 'Landfall', 'capital', 'hb', 800, 930),
    node('hb_town', 'Harborside', 'town', 'hb', 1040, 920),
    node('hb_mine', 'Anchor Quarry', 'production', 'hb', 560, 930),
    node('hb_castle', 'Beachhead Keep', 'castle', 'hb', 800, 790),
    node('hb_farm', 'Newfield Farms', 'food', 'hb', 1080, 780),
    node('hb_tower', 'Hearthwatch', 'outpost', 'hb', 540, 790),
    node('hb_west', 'Saltmere', 'town', 'hb', 400, 930, { tier: 1 }),
    // Neutral land between the Blightmarsh and everyone else
    node('n_market', 'Crossroads Market', 'town', null, 990, 650, { garrison: 15, defense: 3 }),
    node('n_pass', 'Ashen Pass', 'outpost', null, 630, 260),
    node('n_quarry', 'Old Quarry', 'production', null, 980, 300, { garrison: 8 }),
    node('n_keep', 'Broken Keep', 'castle', null, 440, 580, { tier: 0, garrison: 0, defense: 0 }),
    node('n_fort', 'Sunken Fort', 'castle', null, 1180, 620, { garrison: 10, defense: 5 }),
    node('n_river', 'Riverlands', 'food', null, 660, 740, { tier: 2, garrison: 8 }),
    node('n_fen', 'Frostfen', 'food', null, 430, 460),
    node('n_vein', 'Goldvein', 'production', null, 1220, 430, { tier: 1 }),
  ];
  const homeEdges = (k) => [['cap', 'town'], ['cap', 'mine'], ['cap', 'castle'], ['town', 'castle'], ['castle', 'farm'], ['castle', 'tower']]
    .map(([a, b]) => [k + '_' + a, k + '_' + b]);
  const edges = [
    ...['el', 'or', 'go', 'ko', 'hb'].flatMap(homeEdges),
    ['el_town', 'el_farm'], ['el_mine', 'el_tower'], ['or_town', 'or_farm'], ['or_mine', 'or_tower'],
    ['go_town', 'go_tower'], ['go_mine', 'go_farm'], ['ko_town', 'ko_farm'], ['ko_mine', 'ko_castle'],
    ['hb_town', 'hb_farm'], ['hb_mine', 'hb_tower'], ['hb_mine', 'hb_west'], ['hb_tower', 'hb_west'],
    // Undead home: the capital sits in the middle with a node facing each way
    ['un_cap', 'un_town'], ['un_cap', 'un_mine'], ['un_cap', 'un_castle'], ['un_cap', 'un_tower'],
    ['un_town', 'un_farm'], ['un_castle', 'un_farm'], ['un_town', 'un_tower'], ['un_mine', 'un_tower'],
    // Borders: north and west
    ['ko_mine', 'el_town'], ['ko_tower', 'n_fen'], ['ko_tower', 'n_pass'], ['ko_farm', 'n_pass'],
    ['or_farm', 'n_pass'], ['or_castle', 'n_quarry'], ['or_tower', 'n_vein'], ['or_tower', 'go_tower'],
    ['el_farm', 'n_fen'], ['el_castle', 'n_keep'], ['el_tower', 'hb_west'], ['el_castle', 'hb_tower'],
    // Borders: around the Blightmarsh
    ['un_tower', 'n_pass'], ['un_tower', 'n_quarry'], ['un_town', 'n_fen'], ['un_town', 'n_keep'],
    ['un_mine', 'n_quarry'], ['un_mine', 'n_market'], ['un_castle', 'n_market'], ['un_castle', 'hb_castle'],
    ['un_farm', 'n_river'], ['un_farm', 'n_keep'],
    // Borders: east and south
    ['n_quarry', 'n_vein'], ['go_tower', 'n_vein'], ['go_castle', 'n_fort'], ['go_farm', 'n_fort'],
    ['n_fort', 'n_market'], ['hb_castle', 'n_market'], ['hb_farm', 'n_market'], ['hb_farm', 'n_fort'],
    ['hb_tower', 'n_river'], ['hb_tower', 'n_keep'], ['n_keep', 'n_river'],
  ];
  const settled = { name: 'Hearthbound continent (settled start)', kingdoms, nodes, edges };
  // Ruins start: every faction holds only its capital; all other land is unowned, empty Tier 0 ruins.
  const ruins = {
    name: 'Hearthbound continent (ruins start)', kingdoms, edges,
    nodes: nodes.map((n) => (n.type === 'capital' ? n : Object.assign({}, n, { owner: null, tier: 0, garrison: 0, defense: 0 }))),
  };
  // Ruins start plus the Dwarves in the north-east corner, between the Orcs and Goblins. Their two resource nodes sit
  // behind the capital and touch nothing else, so no one can reach them (capitals can't be captured).
  const dw = (id, name, type, x, y, over) => Object.assign({ id, name, type, owner: null, home: 'dw', tier: 0, garrison: 0, defense: 0, morale: 10, devastation: 0, x, y }, over || {});
  const dwarves = {
    name: 'Hearthbound continent with Dwarves (ruins start)',
    kingdoms: kingdoms.concat([Object.assign({}, start, { id: 'dw', name: 'Dwarves', color: '#8A6E4B', growth: 'slow', ai: 'dwarf', foes: ['or', 'go'], noFreeRebuild: true })]),
    nodes: ruins.nodes.concat([
      dw('dw_cap', 'Karak Varn', 'capital', 1450, 190, { owner: 'dw', tier: 5, garrison: 30, defense: 10 }),
      dw('dw_mine', 'Deepdelve Mine', 'production', 1610, 90),
      dw('dw_farm', 'Fungal Halls', 'food', 1620, 270),
    ]),
    edges: edges.concat([['dw_cap', 'dw_mine'], ['dw_cap', 'dw_farm'], ['dw_cap', 'or_mine'], ['dw_cap', 'or_tower'], ['dw_cap', 'go_town']]),
  };
  return { ruins, settled, dwarves };
});
